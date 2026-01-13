// Package gobackend provides timeout execution for extension JS code
package gobackend

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/dop251/goja"
)

// JSExecutionError represents an error during JS execution
type JSExecutionError struct {
	Message   string
	IsTimeout bool
}

func (e *JSExecutionError) Error() string {
	return e.Message
}

// RunWithTimeout executes JavaScript code with a timeout
// Returns the result value and any error (including timeout)
func RunWithTimeout(vm *goja.Runtime, script string, timeout time.Duration) (goja.Value, error) {
	if timeout <= 0 {
		timeout = DefaultJSTimeout
	}

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	// Channel to receive result
	type result struct {
		value goja.Value
		err   error
	}
	resultCh := make(chan result, 1)

	// Track if we've interrupted
	var interrupted bool
	var interruptMu sync.Mutex

	// Run script in goroutine
	go func() {
		defer func() {
			if r := recover(); r != nil {
				// Check if this was our interrupt
				interruptMu.Lock()
				wasInterrupted := interrupted
				interruptMu.Unlock()

				if wasInterrupted {
					resultCh <- result{nil, &JSExecutionError{
						Message:   "execution timeout exceeded",
						IsTimeout: true,
					}}
				} else {
					resultCh <- result{nil, fmt.Errorf("panic during execution: %v", r)}
				}
			}
		}()

		val, err := vm.RunString(script)
		resultCh <- result{val, err}
	}()

	// Wait for result or timeout
	select {
	case res := <-resultCh:
		return res.value, res.err
	case <-ctx.Done():
		// Timeout - interrupt the VM
		interruptMu.Lock()
		interrupted = true
		interruptMu.Unlock()

		vm.Interrupt("execution timeout")

		// Wait a bit for the goroutine to finish
		select {
		case res := <-resultCh:
			// If we got a result after interrupt, it might be the timeout error
			if res.err != nil {
				return nil, res.err
			}
			return nil, &JSExecutionError{
				Message:   "execution timeout exceeded",
				IsTimeout: true,
			}
		case <-time.After(1 * time.Second):
			// Force return timeout error
			return nil, &JSExecutionError{
				Message:   "execution timeout exceeded (force)",
				IsTimeout: true,
			}
		}
	}
}

// RunWithTimeoutAndRecover runs JS with timeout and clears interrupt state after
// This should be used when you want to continue using the VM after a timeout
func RunWithTimeoutAndRecover(vm *goja.Runtime, script string, timeout time.Duration) (goja.Value, error) {
	result, err := RunWithTimeout(vm, script, timeout)

	// Clear any interrupt state so VM can be reused
	vm.ClearInterrupt()

	return result, err
}

// IsTimeoutError checks if an error is a timeout error
func IsTimeoutError(err error) bool {
	if jsErr, ok := err.(*JSExecutionError); ok {
		return jsErr.IsTimeout
	}
	return false
}
