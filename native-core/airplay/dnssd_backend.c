/*
 * No-op mDNS backend for the vendored UxPlay dnssd.c.
 *
 * On Android we advertise the AirPlay service via NsdManager (Kotlin side), so
 * we do not run UxPlay's native responder. dnssd.c still expects a backend to
 * exist; these satisfy it:
 *   - dnssd_private_init/destroy: the responder lifecycle -> no-op.
 *   - dnssd_get_raop_txt/airplay_txt: the DNS-SD TXT blobs, only used by a rare
 *     "/info?txtAirPlay" query variant; empty is accepted (senders read the
 *     plist features/name/pk, which come from the real getters in dnssd.c).
 *
 * This is our own code, not vendored GPL source.
 */
#include "dnssd.h"

/* A distinct non-null sentinel so dnssd.c's null-check passes. */
static int g_backend_sentinel = 1;

void *dnssd_private_init(dnssd_t *dnssd_public, int *error) {
    (void) dnssd_public;
    if (error) *error = 0;
    return &g_backend_sentinel;
}

void dnssd_private_destroy(void *dnssd_private) {
    (void) dnssd_private;
}

const char *dnssd_get_raop_txt(dnssd_t *dnssd, int *length) {
    (void) dnssd;
    if (length) *length = 0;
    return "";
}

const char *dnssd_get_airplay_txt(dnssd_t *dnssd, int *length) {
    (void) dnssd;
    if (length) *length = 0;
    return "";
}
