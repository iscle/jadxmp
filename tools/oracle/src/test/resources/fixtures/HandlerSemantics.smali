.class public Lfixtures/HandlerSemantics;
.super Ljava/lang/Object;

.method public static run(I)V
    .registers 6
    :string_start
    const-string v4, "value"
    :string_end
    .catch Ljava/io/IOException; {:string_start .. :string_end} :impossible

    :outer_start
    const/4 v1, 0x1
    invoke-static {p0, v1}, Lcom/jadxmp/oracle/HandlerTestHooks;->step(II)V
    :outer_end
    .catch Ljava/io/IOException; {:outer_start .. :outer_end} :io_handler

    :inner_start
    const/4 v1, 0x2
    invoke-static {p0, v1}, Lcom/jadxmp/oracle/HandlerTestHooks;->step(II)V
    :inner_end
    .catchall {:inner_start .. :inner_end} :inner_handler

    :tail_start
    const/4 v1, 0x3
    invoke-static {p0, v1}, Lcom/jadxmp/oracle/HandlerTestHooks;->step(II)V
    :tail_end
    .catch Ljava/io/IOException; {:tail_start .. :tail_end} :io_handler
    goto :done

    :inner_handler
    move-exception v0
    move-object v2, v0
    :cleanup_start
    const/4 v1, 0x4
    invoke-static {p0, v1}, Lcom/jadxmp/oracle/HandlerTestHooks;->step(II)V
    :cleanup_end
    .catchall {:cleanup_start .. :cleanup_end} :suppressed_handler
    goto :rethrow

    :suppressed_handler
    move-exception v0
    move-object v3, v0
    :rethrow_start
    invoke-virtual {v2, v3}, Ljava/lang/Throwable;->addSuppressed(Ljava/lang/Throwable;)V
    :rethrow
    throw v2
    :rethrow_end
    .catch Ljava/io/IOException; {:rethrow_start .. :rethrow_end} :io_handler

    :io_handler
    move-exception v0
    const/4 v1, 0x5
    invoke-static {v1}, Lcom/jadxmp/oracle/HandlerTestHooks;->observe(I)V
    goto :done

    :impossible
    move-exception v0
    const/16 v1, 0x63
    invoke-static {v1}, Lcom/jadxmp/oracle/HandlerTestHooks;->observe(I)V
    :done
    return-void
.end method

.method public static shared(I)V
    .registers 3
    :first_start
    const/4 v0, 0x6
    invoke-static {p0, v0}, Lcom/jadxmp/oracle/HandlerTestHooks;->unchecked(II)V
    :first_end
    .catch Ljava/lang/RuntimeException; {:first_start .. :first_end} :shared_handler
    const/4 v0, 0x7
    invoke-static {p0, v0}, Lcom/jadxmp/oracle/HandlerTestHooks;->unchecked(II)V
    :second_start
    const/16 v0, 0x8
    invoke-static {p0, v0}, Lcom/jadxmp/oracle/HandlerTestHooks;->unchecked(II)V
    :second_end
    .catch Ljava/lang/RuntimeException; {:second_start .. :second_end} :shared_handler
    return-void
    :shared_handler
    move-exception v0
    const/16 v0, 0x9
    invoke-static {v0}, Lcom/jadxmp/oracle/HandlerTestHooks;->observe(I)V
    return-void
.end method

.method public static nested(I)V
    .registers 3
    :main_start
    const/4 v0, 0x1
    invoke-static {p0, v0}, Lcom/jadxmp/oracle/HandlerTestHooks;->unchecked(II)V
    :main_end
    .catch Ljava/lang/RuntimeException; {:main_start .. :main_end} :outer_handler
    return-void
    :outer_handler
    :nested_start
    move-exception v0
    const/4 v0, 0x2
    invoke-static {p0, v0}, Lcom/jadxmp/oracle/HandlerTestHooks;->unchecked(II)V
    :nested_end
    .catchall {:nested_start .. :nested_end} :nested_handler
    return-void
    :nested_handler
    move-exception v0
    const/4 v0, 0x3
    invoke-static {v0}, Lcom/jadxmp/oracle/HandlerTestHooks;->observe(I)V
    return-void
.end method
