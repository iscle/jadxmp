.class public Lfixtures/ExceptionState;
.super Ljava/lang/Object;

.method public static afterCall(I)I
    .registers 3
    const/4 v0, 0x1
    :start
    invoke-static {p0, v0}, Lcom/jadxmp/oracle/ExceptionStateHooks;->checkpoint(II)V
    const/4 v0, 0x2
    :end
    .catch Ljava/lang/RuntimeException; {:start .. :end} :caught
    return v0
    :caught
    move-exception v1
    return v0
.end method

.method public static arrayLength([I)I
    .registers 3
    const/4 v0, 0x7
    :start
    array-length v0, p0
    :end
    .catch Ljava/lang/NullPointerException; {:start .. :end} :caught
    return v0
    :caught
    move-exception v1
    return v0
.end method

.method public static cast(Ljava/lang/Object;)Ljava/lang/Object;
    .registers 2
    :start
    check-cast p0, Ljava/lang/String;
    :end
    .catch Ljava/lang/ClassCastException; {:start .. :end} :caught
    return-object p0
    :caught
    move-exception v0
    return-object p0
.end method

.method public static callResult(I)I
    .registers 3
    const/16 v0, 0x9
    :start
    invoke-static {p0}, Lcom/jadxmp/oracle/ExceptionStateHooks;->value(I)I
    move-result v0
    :end
    .catch Ljava/lang/RuntimeException; {:start .. :end} :caught
    return v0
    :caught
    move-exception v1
    return v0
.end method

.method public static wide([J)J
    .registers 5
    const-wide/16 v0, 0x9
    const/4 v2, 0x0
    :start
    aget-wide v0, p0, v2
    :end
    .catch Ljava/lang/RuntimeException; {:start .. :end} :caught
    return-wide v0
    :caught
    move-exception v3
    return-wide v0
.end method

.method public static loop(I)I
    .registers 4
    const/4 v0, 0x0
    const/4 v1, 0x3
    :start
    :loop
    add-int/lit8 v0, v0, 0x1
    invoke-static {p0, v0}, Lcom/jadxmp/oracle/ExceptionStateHooks;->checkpoint(II)V
    if-lt v0, v1, :loop
    :end
    .catch Ljava/lang/RuntimeException; {:start .. :end} :caught
    return v0
    :caught
    move-exception v2
    return v0
.end method

.method public static nested(I)I
    .registers 4
    const/16 v0, 0xa
    const/4 v1, 0x1
    :start
    invoke-static {p0, v1}, Lcom/jadxmp/oracle/ExceptionStateHooks;->checkpoint(II)V
    :end
    .catch Ljava/lang/RuntimeException; {:start .. :end} :outer
    return v0
    :outer
    move-exception v2
    const/16 v0, 0x14
    const/4 v1, 0x2
    :nested_start
    invoke-static {p0, v1}, Lcom/jadxmp/oracle/ExceptionStateHooks;->checkpoint(II)V
    const/16 v0, 0x1e
    :nested_end
    .catch Ljava/lang/RuntimeException; {:nested_start .. :nested_end} :inner
    return v0
    :inner
    move-exception v2
    return v0
.end method

.method public static wideParameter(JI)J
    .registers 5
    :start
    invoke-static {p2}, Lcom/jadxmp/oracle/ExceptionStateHooks;->value(I)I
    move-result v0
    add-long/2addr p0, p0
    :end
    .catch Ljava/lang/RuntimeException; {:start .. :end} :caught
    return-wide p0
    :caught
    move-exception v1
    return-wide p0
.end method

.method public instance(I)I
    .registers 4
    const/16 v0, 0x9
    :start
    invoke-static {p1}, Lcom/jadxmp/oracle/ExceptionStateHooks;->value(I)I
    move-result v0
    :end
    .catch Ljava/lang/RuntimeException; {:start .. :end} :caught
    return v0
    :caught
    move-exception v1
    return v0
.end method

.method public static protectedReturn(I)I
    .registers 3
    const/4 v0, 0x1
    :start
    invoke-static {p0, v0}, Lcom/jadxmp/oracle/ExceptionStateHooks;->checkpoint(II)V
    return v0
    :end
    .catch Ljava/lang/RuntimeException; {:start .. :end} :caught
    :caught
    move-exception v1
    const/4 v0, 0x2
    return v0
.end method

.method public static protectedBranchReturn(I)I
    .registers 3
    const/4 v0, 0x1
    :start
    if-eqz p0, :return
    invoke-static {p0, v0}, Lcom/jadxmp/oracle/ExceptionStateHooks;->checkpoint(II)V
    :return
    return v0
    :end
    .catch Ljava/lang/RuntimeException; {:start .. :end} :caught
    :caught
    move-exception v1
    const/4 v0, 0x2
    return v0
.end method
