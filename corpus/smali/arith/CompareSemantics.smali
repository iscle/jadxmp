# jadxmp regression fixture: DEX comparisons must preserve NaN bias and signed-zero equality.
.class public Lcmp/CompareSemantics;
.super Ljava/lang/Object;

.method public static lowFloat(FF)I
    .registers 3
    cmpl-float v0, p0, p1
    return v0
.end method

.method public static highFloat(FF)I
    .registers 3
    cmpg-float v0, p0, p1
    return v0
.end method

.method public static lowDouble(DD)I
    .registers 5
    cmpl-double v0, p0, p2
    return v0
.end method

.method public static highDouble(DD)I
    .registers 5
    cmpg-double v0, p0, p2
    return v0
.end method

.method public static compareLong(JJ)I
    .registers 5
    cmp-long v0, p0, p2
    return v0
.end method

.method public static nested(FF)I
    .registers 3
    cmpl-float v0, p0, p1
    add-int/lit8 v0, v0, 0x5
    return v0
.end method

.field public static counter:I

.method public static next()F
    .registers 1
    sget v0, Lcmp/CompareSemantics;->counter:I
    add-int/lit8 v0, v0, 0x1
    sput v0, Lcmp/CompareSemantics;->counter:I
    int-to-float v0, v0
    return v0
.end method

.method public static ordered()I
    .registers 2
    invoke-static {}, Lcmp/CompareSemantics;->next()F
    move-result v0
    invoke-static {}, Lcmp/CompareSemantics;->next()F
    move-result v1
    cmpl-float v0, v0, v1
    return v0
.end method
