//
// Zalith Lite: big-core affinity helpers (CORE_PLAN_A F1-4)
//

#ifndef ZALITH_BIGCOREAFFINITY_H
#define ZALITH_BIGCOREAFFINITY_H

/**
 * Apply an affinity mask built from ZALITH_CPU_IDS to the calling thread.
 *
 * @param ids comma/range list such as "4,5,6,7" or "4-7". May be NULL/empty.
 * @return the number of CPUs in the applied mask, or -1 when nothing was applied
 *         (no ids, parse failure, or the intersection with the current mask has
 *         fewer than 2 CPUs). Never fatal: failures only log.
 */
int zalith_apply_affinity(const char *ids);

/** Restore the mask saved by a successful zalith_apply_affinity(). Safe to call always. */
void zalith_restore_affinity(void);

#endif //ZALITH_BIGCOREAFFINITY_H
