#ifndef LAB_SYSTEM_DNS_H
#define LAB_SYSTEM_DNS_H
#include <stddef.h>
// Read before installing the tunnel's DNS settings. Newline-separated numeric
// addresses, including IPv6 scope IDs. Returns count, or -1 on failure.
int lab_system_dns(char *output, size_t capacity);
#endif
