.class public Lfixtures/ExpressionOrder;
.super Ljava/lang/Object;

.method public static store()V
    .registers 3
    invoke-static {}, Lcom/jadxmp/oracle/ExpressionOrderHooks;->first()I
    move-result v0
    invoke-static {}, Lcom/jadxmp/oracle/ExpressionOrderHooks;->array()[I
    move-result-object v1
    invoke-static {}, Lcom/jadxmp/oracle/ExpressionOrderHooks;->index()I
    move-result v2
    aput v0, v1, v2
    return-void
.end method

.method public static divide(I)I
    .registers 3
    const/16 v0, 12
    div-int v1, v0, p0
    invoke-static {}, Lcom/jadxmp/oracle/ExpressionOrderHooks;->effect()V
    return v1
.end method

.method public static remainder(I)I
    .registers 3
    const/16 v0, 12
    rem-int v1, v0, p0
    invoke-static {}, Lcom/jadxmp/oracle/ExpressionOrderHooks;->effect()V
    return v1
.end method

.method public static length([I)I
    .registers 2
    array-length v0, p0
    invoke-static {}, Lcom/jadxmp/oracle/ExpressionOrderHooks;->effect()V
    return v0
.end method

.method public static reversed()I
    .registers 2
    invoke-static {}, Lcom/jadxmp/oracle/ExpressionOrderHooks;->first()I
    move-result v0
    invoke-static {}, Lcom/jadxmp/oracle/ExpressionOrderHooks;->second()I
    move-result v1
    invoke-static {v1, v0}, Lcom/jadxmp/oracle/ExpressionOrderHooks;->combine(II)I
    move-result v0
    return v0
.end method

.method public static nested()I
    .registers 2
    invoke-static {}, Lcom/jadxmp/oracle/ExpressionOrderHooks;->first()I
    move-result v0
    const/4 v1, 1
    add-int v0, v0, v1
    invoke-static {}, Lcom/jadxmp/oracle/ExpressionOrderHooks;->effect()V
    return v0
.end method
