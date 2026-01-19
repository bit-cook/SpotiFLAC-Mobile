// Package gobackend provides Storage and Credentials API for extension runtime
package gobackend

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"

	"github.com/dop251/goja"
)

// ==================== Storage API ====================

// getStoragePath returns the path to the extension's storage file
func (r *ExtensionRuntime) getStoragePath() string {
	return filepath.Join(r.dataDir, "storage.json")
}

// loadStorage loads the storage data from disk
func (r *ExtensionRuntime) loadStorage() (map[string]interface{}, error) {
	storagePath := r.getStoragePath()
	data, err := os.ReadFile(storagePath)
	if err != nil {
		if os.IsNotExist(err) {
			return make(map[string]interface{}), nil
		}
		return nil, err
	}

	var storage map[string]interface{}
	if err := json.Unmarshal(data, &storage); err != nil {
		return nil, err
	}

	return storage, nil
}

// saveStorage saves the storage data to disk
func (r *ExtensionRuntime) saveStorage(storage map[string]interface{}) error {
	storagePath := r.getStoragePath()
	data, err := json.MarshalIndent(storage, "", "  ")
	if err != nil {
		return err
	}

	return os.WriteFile(storagePath, data, 0644)
}

// storageGet retrieves a value from storage
func (r *ExtensionRuntime) storageGet(call goja.FunctionCall) goja.Value {
	if len(call.Arguments) < 1 {
		return goja.Undefined()
	}

	key := call.Arguments[0].String()

	storage, err := r.loadStorage()
	if err != nil {
		GoLog("[Extension:%s] Storage load error: %v\n", r.extensionID, err)
		return goja.Undefined()
	}

	value, exists := storage[key]
	if !exists {
		// Return default value if provided
		if len(call.Arguments) > 1 {
			return call.Arguments[1]
		}
		return goja.Undefined()
	}

	return r.vm.ToValue(value)
}

// storageSet stores a value in storage
func (r *ExtensionRuntime) storageSet(call goja.FunctionCall) goja.Value {
	if len(call.Arguments) < 2 {
		return r.vm.ToValue(false)
	}

	key := call.Arguments[0].String()
	value := call.Arguments[1].Export()

	storage, err := r.loadStorage()
	if err != nil {
		GoLog("[Extension:%s] Storage load error: %v\n", r.extensionID, err)
		return r.vm.ToValue(false)
	}

	storage[key] = value

	if err := r.saveStorage(storage); err != nil {
		GoLog("[Extension:%s] Storage save error: %v\n", r.extensionID, err)
		return r.vm.ToValue(false)
	}

	return r.vm.ToValue(true)
}

// storageRemove removes a value from storage
func (r *ExtensionRuntime) storageRemove(call goja.FunctionCall) goja.Value {
	if len(call.Arguments) < 1 {
		return r.vm.ToValue(false)
	}

	key := call.Arguments[0].String()

	storage, err := r.loadStorage()
	if err != nil {
		GoLog("[Extension:%s] Storage load error: %v\n", r.extensionID, err)
		return r.vm.ToValue(false)
	}

	delete(storage, key)

	if err := r.saveStorage(storage); err != nil {
		GoLog("[Extension:%s] Storage save error: %v\n", r.extensionID, err)
		return r.vm.ToValue(false)
	}

	return r.vm.ToValue(true)
}

// ==================== Credentials API (Encrypted Storage) ====================

// getCredentialsPath returns the path to the extension's encrypted credentials file
func (r *ExtensionRuntime) getCredentialsPath() string {
	return filepath.Join(r.dataDir, ".credentials.enc")
}

// getSaltPath returns the path to the extension's encryption salt file
func (r *ExtensionRuntime) getSaltPath() string {
	return filepath.Join(r.dataDir, ".cred_salt")
}

// getOrCreateSalt gets existing salt or creates a new random one
func (r *ExtensionRuntime) getOrCreateSalt() ([]byte, error) {
	saltPath := r.getSaltPath()

	salt, err := os.ReadFile(saltPath)
	if err == nil && len(salt) == 32 {
		return salt, nil
	}

	salt = make([]byte, 32)
	if _, err := io.ReadFull(rand.Reader, salt); err != nil {
		return nil, fmt.Errorf("failed to generate salt: %w", err)
	}

	if err := os.WriteFile(saltPath, salt, 0600); err != nil {
		return nil, fmt.Errorf("failed to save salt: %w", err)
	}

	return salt, nil
}

// getEncryptionKey derives an encryption key from extension ID + random salt
func (r *ExtensionRuntime) getEncryptionKey() ([]byte, error) {
	// Get or create per-installation random salt
	salt, err := r.getOrCreateSalt()
	if err != nil {
		return nil, err
	}

	// Combine extension ID + random salt for key derivation
	// This makes each installation unique, preventing mass decryption attacks
	combined := append([]byte(r.extensionID), salt...)
	hash := sha256.Sum256(combined)
	return hash[:], nil
}

// loadCredentials loads and decrypts credentials from disk
func (r *ExtensionRuntime) loadCredentials() (map[string]interface{}, error) {
	credPath := r.getCredentialsPath()
	data, err := os.ReadFile(credPath)
	if err != nil {
		if os.IsNotExist(err) {
			return make(map[string]interface{}), nil
		}
		return nil, err
	}

	// Decrypt the data
	key, err := r.getEncryptionKey()
	if err != nil {
		return nil, fmt.Errorf("failed to get encryption key: %w", err)
	}
	decrypted, err := decryptAES(data, key)
	if err != nil {
		return nil, fmt.Errorf("failed to decrypt credentials: %w", err)
	}

	var creds map[string]interface{}
	if err := json.Unmarshal(decrypted, &creds); err != nil {
		return nil, err
	}

	return creds, nil
}

// saveCredentials encrypts and saves credentials to disk
func (r *ExtensionRuntime) saveCredentials(creds map[string]interface{}) error {
	data, err := json.Marshal(creds)
	if err != nil {
		return err
	}

	key, err := r.getEncryptionKey()
	if err != nil {
		return fmt.Errorf("failed to get encryption key: %w", err)
	}
	encrypted, err := encryptAES(data, key)
	if err != nil {
		return fmt.Errorf("failed to encrypt credentials: %w", err)
	}

	credPath := r.getCredentialsPath()
	return os.WriteFile(credPath, encrypted, 0600) // Restrictive permissions
}

// credentialsStore stores an encrypted credential
func (r *ExtensionRuntime) credentialsStore(call goja.FunctionCall) goja.Value {
	if len(call.Arguments) < 2 {
		return r.vm.ToValue(map[string]interface{}{
			"success": false,
			"error":   "key and value are required",
		})
	}

	key := call.Arguments[0].String()
	value := call.Arguments[1].Export()

	creds, err := r.loadCredentials()
	if err != nil {
		GoLog("[Extension:%s] Credentials load error: %v\n", r.extensionID, err)
		return r.vm.ToValue(map[string]interface{}{
			"success": false,
			"error":   err.Error(),
		})
	}

	creds[key] = value

	if err := r.saveCredentials(creds); err != nil {
		GoLog("[Extension:%s] Credentials save error: %v\n", r.extensionID, err)
		return r.vm.ToValue(map[string]interface{}{
			"success": false,
			"error":   err.Error(),
		})
	}

	return r.vm.ToValue(map[string]interface{}{
		"success": true,
	})
}

// credentialsGet retrieves a decrypted credential
func (r *ExtensionRuntime) credentialsGet(call goja.FunctionCall) goja.Value {
	if len(call.Arguments) < 1 {
		return goja.Undefined()
	}

	key := call.Arguments[0].String()

	creds, err := r.loadCredentials()
	if err != nil {
		GoLog("[Extension:%s] Credentials load error: %v\n", r.extensionID, err)
		return goja.Undefined()
	}

	value, exists := creds[key]
	if !exists {
		// Return default value if provided
		if len(call.Arguments) > 1 {
			return call.Arguments[1]
		}
		return goja.Undefined()
	}

	return r.vm.ToValue(value)
}

// credentialsRemove removes a credential
func (r *ExtensionRuntime) credentialsRemove(call goja.FunctionCall) goja.Value {
	if len(call.Arguments) < 1 {
		return r.vm.ToValue(false)
	}

	key := call.Arguments[0].String()

	creds, err := r.loadCredentials()
	if err != nil {
		GoLog("[Extension:%s] Credentials load error: %v\n", r.extensionID, err)
		return r.vm.ToValue(false)
	}

	delete(creds, key)

	if err := r.saveCredentials(creds); err != nil {
		GoLog("[Extension:%s] Credentials save error: %v\n", r.extensionID, err)
		return r.vm.ToValue(false)
	}

	return r.vm.ToValue(true)
}

// credentialsHas checks if a credential exists
func (r *ExtensionRuntime) credentialsHas(call goja.FunctionCall) goja.Value {
	if len(call.Arguments) < 1 {
		return r.vm.ToValue(false)
	}

	key := call.Arguments[0].String()

	creds, err := r.loadCredentials()
	if err != nil {
		return r.vm.ToValue(false)
	}

	_, exists := creds[key]
	return r.vm.ToValue(exists)
}

// ==================== Crypto Utilities ====================

// encryptAES encrypts data using AES-GCM
func encryptAES(plaintext []byte, key []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}

	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}

	nonce := make([]byte, gcm.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return nil, err
	}

	ciphertext := gcm.Seal(nonce, nonce, plaintext, nil)
	return ciphertext, nil
}

// decryptAES decrypts data using AES-GCM
func decryptAES(ciphertext []byte, key []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}

	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}

	nonceSize := gcm.NonceSize()
	if len(ciphertext) < nonceSize {
		return nil, fmt.Errorf("ciphertext too short")
	}

	nonce, ciphertext := ciphertext[:nonceSize], ciphertext[nonceSize:]
	plaintext, err := gcm.Open(nil, nonce, ciphertext, nil)
	if err != nil {
		return nil, err
	}

	return plaintext, nil
}
