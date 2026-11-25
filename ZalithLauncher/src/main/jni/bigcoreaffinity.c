//
// Created by maks on 19.06.2023.
// Rewritten for Zalith Lite CORE_PLAN_A F1-4: apply a big-core mask parsed from
// ZALITH_CPU_IDS instead of pinning everything to the single fastest CPU.
//

#define _GNU_SOURCE // CPU_SET / CPU_COUNT / sched_getaffinity

#include <errno.h>
#include <sched.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static cpu_set_t g_saved_mask;
static bool g_saved_mask_valid = false;
static bool g_affinity_applied = false;

static void print_cpu_set(const char *label, const cpu_set_t *set) {
    printf("bigcore: %s = [", label);
    bool first = true;
    for (int cpu = 0; cpu < CPU_SETSIZE; cpu++) {
        if (!CPU_ISSET(cpu, set)) continue;
        printf("%s%d", first ? "" : ",", cpu);
        first = false;
    }
    printf("] count=%d\n", CPU_COUNT(set));
}

/**
 * Parse "4,5,6,7" or "4-7" (mixed forms allowed) into out.
 * Returns false on any malformed token or out-of-range id.
 */
static bool parse_cpu_ids(const char *ids, cpu_set_t *out) {
    if (ids == NULL) return false;

    CPU_ZERO(out);
    bool any = false;
    const char *cursor = ids;

    while (*cursor != '\0') {
        while (*cursor == ',' || *cursor == ' ' || *cursor == '\t') cursor++;
        if (*cursor == '\0') break;

        char *end = NULL;
        errno = 0;
        long first = strtol(cursor, &end, 10);
        if (end == cursor || errno != 0) return false;

        long last = first;
        cursor = end;
        if (*cursor == '-') {
            cursor++;
            errno = 0;
            last = strtol(cursor, &end, 10);
            if (end == cursor || errno != 0) return false;
            cursor = end;
        }

        if (first < 0 || last < 0 || first >= CPU_SETSIZE || last >= CPU_SETSIZE || first > last) {
            printf("bigcore: invalid cpu id range %ld-%ld\n", first, last);
            return false;
        }

        for (long cpu = first; cpu <= last; cpu++) {
            CPU_SET((int) cpu, out);
            any = true;
        }

        while (*cursor == ' ' || *cursor == '\t') cursor++;
        if (*cursor != '\0' && *cursor != ',') {
            printf("bigcore: unexpected character '%c' in ZALITH_CPU_IDS\n", *cursor);
            return false;
        }
    }

    return any;
}

int zalith_apply_affinity(const char *ids) {
    if (ids == NULL || ids[0] == '\0') {
        printf("bigcore: ZALITH_CPU_IDS not set, keeping default affinity\n");
        return -1;
    }

    cpu_set_t requested;
    if (!parse_cpu_ids(ids, &requested)) {
        printf("bigcore: failed to parse ZALITH_CPU_IDS='%s', keeping default affinity\n", ids);
        return -1;
    }

    cpu_set_t current;
    CPU_ZERO(&current);
    if (sched_getaffinity(0, sizeof(cpu_set_t), &current) != 0) {
        printf("bigcore: sched_getaffinity failed: %s, keeping default affinity\n", strerror(errno));
        return -1;
    }
    print_cpu_set("current affinity", &current);

    /* Never widen the mask: intersect the requested big cores with what the
     * Android cpuset actually allows this process to run on. */
    cpu_set_t target;
    CPU_ZERO(&target);
    for (int cpu = 0; cpu < CPU_SETSIZE; cpu++) {
        if (CPU_ISSET(cpu, &requested) &&
            CPU_ISSET(cpu, &current)) {
            CPU_SET(cpu, &target);
        }
    }

    int target_count = CPU_COUNT(&target);
    print_cpu_set("target affinity", &target);
    if (target_count < 2) {
        printf("bigcore: only %d usable CPU(s) after intersecting with allowed mask, "
               "keeping default affinity\n", target_count);
        return -1;
    }

    if (sched_setaffinity(0, sizeof(cpu_set_t), &target) != 0) {
        printf("bigcore: sched_setaffinity failed: %s, keeping default affinity\n", strerror(errno));
        return -1;
    }

    g_saved_mask = current;
    g_saved_mask_valid = true;
    g_affinity_applied = true;
    printf("bigcore: applied affinity to %d CPU(s)\n", target_count);
    return target_count;
}

void zalith_restore_affinity(void) {
    if (!g_affinity_applied) return;

    g_affinity_applied = false;
    if (!g_saved_mask_valid) return;

    if (sched_setaffinity(0, sizeof(cpu_set_t), &g_saved_mask) != 0) {
        printf("bigcore: failed to restore original affinity: %s\n", strerror(errno));
        return;
    }
    print_cpu_set("restored affinity", &g_saved_mask);
    g_saved_mask_valid = false;
}
