#include "SystemDNS.h"
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/nameser.h>
#include <netdb.h>
#include <resolv.h>
#include <string.h>

int lab_system_dns(char *output, size_t capacity) {
    if (!output || !capacity) return -1;
    output[0] = '\0';
    struct __res_state state = {0};
    if (res_ninit(&state) != 0) return -1;
    union res_sockaddr_union servers[MAXNS] = {0};
    int count = res_getservers(&state, servers, MAXNS);
    res_ndestroy(&state);
    size_t used = 0;
    int copied = 0;
    for (int i = 0; i < count && i < MAXNS; i++) {
        const struct sockaddr *address = (const struct sockaddr *)&servers[i];
        socklen_t length;
        if (address->sa_family == AF_INET) length = sizeof(struct sockaddr_in);
        else if (address->sa_family == AF_INET6) length = sizeof(struct sockaddr_in6);
        else continue;
        char host[NI_MAXHOST];
        if (getnameinfo(address, length, host, sizeof(host), NULL, 0, NI_NUMERICHOST) != 0) continue;
        size_t size = strlen(host);
        if (used + size + 2 > capacity) return -1;
        memcpy(output + used, host, size);
        used += size;
        output[used++] = '\n'; output[used] = '\0';
        copied++;
    }
    return copied;
}
