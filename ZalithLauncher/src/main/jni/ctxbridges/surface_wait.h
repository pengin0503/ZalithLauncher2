//
// Zalith Lite: event-driven ANativeWindow arrival notification (FF-F1-2)
//

#ifndef ZALITH_SURFACE_WAIT_H
#define ZALITH_SURFACE_WAIT_H

#include <stdint.h>

/** Current surface generation. Bumped by setupBridgeWindow() whenever a new ANativeWindow arrives. */
uint64_t zalith_surface_generation(void);

/** Notify all waiters that a new ANativeWindow is available. Call after the window is installed. */
void zalith_surface_generation_bump(void);

/**
 * Wait until the generation differs from expected_generation or timeout_ms elapses.
 *
 * @return the generation observed when the wait ended (unchanged on timeout).
 */
uint64_t zalith_surface_wait_for_change(uint64_t expected_generation, int timeout_ms);

#endif //ZALITH_SURFACE_WAIT_H
