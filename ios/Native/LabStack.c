#include "LabStack.h"
#include "lwip/init.h"
#include "lwip/netif.h"
#include "lwip/ip.h"
#include "lwip/tcp.h"
#include "lwip/priv/tcp_priv.h"
#include <string.h>
#include <stdlib.h>

struct flow { struct tcp_pcb *pcb; uint64_t id; int client_done, remote_done; };
static struct flow flows[64];
static struct netif iface;
static struct tcp_pcb *listener;
static void *context;
static lab_output_fn output_fn;
static lab_accept_fn accept_fn;
static lab_event_fn event_fn;
static uint64_t next_id;
static int initialized, running;
static struct flow *find(uint64_t id) { for (int i=0;i<64;i++) if(flows[i].id==id && flows[i].pcb) return &flows[i]; return NULL; }
static void dispose(struct flow *f) { uint64_t id=f->id; memset(f,0,sizeof(*f)); if(event_fn) event_fn(context,id,4,NULL,0); }
static void failed(void *arg, err_t error) { (void)error; dispose(arg); }
static void try_close(struct flow *f) {
    if(!f->pcb || !f->client_done || !f->remote_done) return;
    struct tcp_pcb *pcb=f->pcb;
    tcp_arg(pcb,NULL); tcp_recv(pcb,NULL); tcp_sent(pcb,NULL); tcp_err(pcb,NULL);
    // Both directions reached EOF; close owns the PCB on success.
    if(tcp_close(pcb)!=ERR_OK) tcp_abort(pcb);
    dispose(f);
}
static err_t received(void *arg, struct tcp_pcb *pcb, struct pbuf *p, err_t error) {
    (void)pcb; (void)error;
    struct flow *f=arg;
    if(!p) { event_fn(context,f->id,2,NULL,0); return ERR_OK; }
    uint8_t *data=malloc(p->tot_len);
    if(!data) return ERR_MEM;
    size_t count=p->tot_len;
    pbuf_copy_partial(p,data,(u16_t)count,0); pbuf_free(p);
    event_fn(context,f->id,1,data,count); free(data);
    return ERR_OK;
}
static err_t acknowledged(void *arg, struct tcp_pcb *pcb, u16_t len) {
    (void)pcb; (void)len; struct flow *f=arg; event_fn(context,f->id,3,NULL,0); return ERR_OK;
}
static err_t accepted(void *arg, struct tcp_pcb *pcb, err_t error) {
    (void)arg; (void)error;
    struct flow *f=NULL;
    for(int i=0;i<64;i++) if(!flows[i].pcb) { f=&flows[i]; break; }
    if(!f || !IP_IS_V6(&pcb->local_ip)) { tcp_abort(pcb); return ERR_ABRT; }
    f->pcb=pcb; f->id=++next_id;
    tcp_arg(pcb,f); tcp_recv(pcb,received); tcp_sent(pcb,acknowledged); tcp_err(pcb,failed);
    tcp_nagle_disable(pcb);
    accept_fn(context,f->id,(const uint8_t *)ip_2_ip6(&pcb->local_ip)->addr,pcb->local_port);
    return ERR_OK;
}
static err_t output6(struct netif *netif, struct pbuf *p, const ip6_addr_t *addr) {
    (void)netif; (void)addr;
    uint8_t data[1500];
    if(p->tot_len>sizeof(data)) return ERR_BUF;
    pbuf_copy_partial(p,data,p->tot_len,0);
    output_fn(context,data,p->tot_len); return ERR_OK;
}
static err_t setup(struct netif *netif) { netif->name[0]='i'; netif->name[1]='6'; netif->mtu=1500; netif->output_ip6=output6; return ERR_OK; }
int lab_stack_start(void *ctx, lab_output_fn output, lab_accept_fn accept, lab_event_fn event) {
    if(running) return -1;
    if(!initialized) { lwip_init(); initialized=1; }
    context=ctx; output_fn=output; accept_fn=accept; event_fn=event;
    memset(&iface,0,sizeof(iface)); memset(flows,0,sizeof(flows));
    if(!netif_add_noaddr(&iface,NULL,setup,ip_input)) return -1;
    ip6_addr_t addr; ip6addr_aton("fd64:64:64:1::2",&addr);
    netif_add_ip6_address(&iface,&addr,NULL); netif_ip6_addr_set_state(&iface,0,IP6_ADDR_PREFERRED);
    netif_set_up(&iface); netif_set_link_up(&iface); netif_set_default(&iface);
    netif_set_flags(&iface,NETIF_FLAG_PRETEND_TCP);
    listener=tcp_new_ip_type(IPADDR_TYPE_V6);
    if(!listener) { netif_remove(&iface); return -1; }
    tcp_bind_netif(listener,&iface); tcp_bind(listener,NULL,0);
    struct tcp_pcb *listening=tcp_listen(listener);
    if(!listening) { tcp_abort(listener); listener=NULL; netif_remove(&iface); return -1; }
    listener=listening; tcp_accept(listener,accepted); running=1; return 0;
}
void lab_stack_input(const uint8_t *data, size_t size) {
    if(!running || size<40 || size>1500 || data[0]>>4!=6) return;
    struct pbuf *p=pbuf_alloc(PBUF_RAW,(u16_t)size,PBUF_RAM);
    if(p) { pbuf_take(p,data,size); if(iface.input(p,&iface)!=ERR_OK) pbuf_free(p); }
}
void lab_stack_tick(void) { if(running) tcp_tmr(); }
int lab_tcp_write(uint64_t id,const uint8_t *data,size_t size) {
    struct flow *f=find(id); if(!f || f->remote_done) return -1;
    if(size>tcp_sndbuf(f->pcb) || size>65535) return 0;
    err_t result=tcp_write(f->pcb,data,(u16_t)size,TCP_WRITE_FLAG_COPY);
    if(result==ERR_MEM) return 0;
    if(result!=ERR_OK) return -1;
    tcp_output(f->pcb); return 1;
}
void lab_tcp_ack(uint64_t id,size_t size) { struct flow *f=find(id); if(f) tcp_recved(f->pcb,(u16_t)size); }
void lab_tcp_eof(uint64_t id) { struct flow *f=find(id); if(f && !f->remote_done) { if(tcp_shutdown(f->pcb,0,1)==ERR_OK) { f->remote_done=1; try_close(f); } } }
void lab_tcp_client_finished(uint64_t id) { struct flow *f=find(id); if(f) { f->client_done=1; try_close(f); } }
void lab_tcp_abort(uint64_t id) { struct flow *f=find(id); if(f) { tcp_arg(f->pcb,NULL); tcp_err(f->pcb,NULL); tcp_abort(f->pcb); dispose(f); } }
void lab_stack_stop(void) {
    if(!running) return;
    for(int i=0;i<64;i++) if(flows[i].pcb) lab_tcp_abort(flows[i].id);
    while(tcp_active_pcbs) { tcp_arg(tcp_active_pcbs,NULL); tcp_err(tcp_active_pcbs,NULL); tcp_abort(tcp_active_pcbs); }
    while(tcp_tw_pcbs) tcp_abort(tcp_tw_pcbs);
    if(listener) tcp_close(listener); listener=NULL; netif_remove(&iface);
    running=0; context=NULL; output_fn=NULL; accept_fn=NULL; event_fn=NULL;
}
