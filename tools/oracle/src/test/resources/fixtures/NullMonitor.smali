.class public Lfixtures/NullMonitor;
.super Ljava/lang/Object;

.method public static run()V
    .registers 2
    const/16 v0, 0xa
    invoke-static {v0}, Lcom/jadxmp/oracle/HandlerTestHooks;->observe(I)V
    const/4 v0, 0x0
    const/4 v1, 0x0
    monitor-enter v0
    monitor-enter v1
    monitor-exit v1
    monitor-exit v0
    const/16 v0, 0xb
    invoke-static {v0}, Lcom/jadxmp/oracle/HandlerTestHooks;->observe(I)V
    return-void
.end method
