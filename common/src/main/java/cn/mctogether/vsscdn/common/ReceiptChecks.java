package cn.mctogether.vsscdn.common;

/** Read-only transport deduplication against receipts owned by VSS. No cache keys or files. */
public final class ReceiptChecks {
    private ReceiptChecks(){}
    public static boolean reusable(long cached,long incoming,long classification,boolean loading){
        return !loading&&cached>0&&incoming>0&&cached==incoming&&classification==Long.MIN_VALUE;
    }
    public static boolean older(long cached,long incoming){return cached>0&&incoming>0&&incoming<cached;}
}
