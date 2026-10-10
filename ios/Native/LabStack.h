#ifndef LAB_STACK_H
#define LAB_STACK_H
#include <stdint.h>
#include <stddef.h>
#include "SystemDNS.h"
// All calls/callbacks run on the provider's single serial queue. No retained buffers.
typedef void (*lab_output_fn)(void *, const uint8_t *, size_t);
typedef void (*lab_accept_fn)(void *, uint64_t, const uint8_t *, uint16_t);
// event: 1=data, 2=client FIN, 3=writable, 4=closed/error.
typedef void (*lab_event_fn)(void *, uint64_t, int, const uint8_t *, size_t);
int lab_stack_start(void *, lab_output_fn, lab_accept_fn, lab_event_fn);
void lab_stack_input(const uint8_t *, size_t);
void lab_stack_tick(void);
void lab_stack_stop(void);
int lab_tcp_write(uint64_t, const uint8_t *, size_t);
void lab_tcp_ack(uint64_t, size_t);
void lab_tcp_eof(uint64_t);
void lab_tcp_client_finished(uint64_t);
void lab_tcp_abort(uint64_t);
#endif
