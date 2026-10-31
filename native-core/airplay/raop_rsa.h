/*
 * Classic AirTunes (RAOP) RSA, as shairport-sync implements it: the sender
 * authenticates the receiver with an Apple-Challenge and wraps the stream's AES
 * key with the receiver's public key (SDP rsaaeskey). Both use the long-public
 * AirPort Express key every open-source AirPlay 1 receiver ships.
 *
 * Beam uses this for macOS audio-only AirPlay: Music wraps its key with
 * FairPlay v2 (fpaeskey) otherwise, which no open implementation can unwrap.
 */
#ifndef RAOP_RSA_H
#define RAOP_RSA_H

#include <stddef.h>

/* Apple-Response for an Apple-Challenge header: signs challenge | local ip | hw addr.
 * Writes an unpadded base64 string into out; returns 0 on success. */
int raop_rsa_apple_response(const char *challenge_b64, const unsigned char *local_ip, int local_iplen,
                            const unsigned char hw_addr[6], char *out, size_t outlen);

/* RSA-OAEP(SHA-1) unwrap of an rsaaeskey blob into the 16-byte AES key; returns 0 on success. */
int raop_rsa_decrypt_aeskey(const unsigned char *in, size_t inlen, unsigned char aeskey[16]);

#endif
