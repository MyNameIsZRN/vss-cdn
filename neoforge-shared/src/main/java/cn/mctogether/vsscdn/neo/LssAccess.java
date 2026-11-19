package cn.mctogether.vsscdn.neo;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerPlayer;
import java.lang.reflect.*;
import java.util.*;

/** Explicit compatibility seam for LSS/VSS 1.21.1, protocol 20. No classes copied from VSS. */
public final class LssAccess {
    private LssAccess(){}
    public static Object call(Object target,String name,Object...args){
        Class<?> type=target instanceof Class<?> c?c:target.getClass();
        for(Class<?> c=type;c!=null;c=c.getSuperclass())for(Method m:c.getDeclaredMethods()){
            if(!m.getName().equals(name)||m.getParameterCount()!=args.length)continue;
            Class<?>[] p=m.getParameterTypes();boolean matches=true;
            for(int i=0;i<p.length;i++)if(args[i]!=null&&!boxed(p[i]).isInstance(args[i]))matches=false;
            if(!matches)continue;
            try{m.setAccessible(true);return m.invoke(target instanceof Class<?>?null:target,args);}
            catch(ReflectiveOperationException e){throw new IllegalStateException("VSS method failed: "+name,e);}
        }
        throw new IllegalStateException("Unsupported VSS method: "+type.getName()+"."+name);
    }
    private static Class<?> boxed(Class<?> c){if(!c.isPrimitive())return c;if(c==int.class)return Integer.class;if(c==long.class)return Long.class;if(c==boolean.class)return Boolean.class;if(c==byte.class)return Byte.class;return c;}
    public static Class<?> type(String name){try{return Class.forName(name);}catch(ClassNotFoundException e){throw new IllegalStateException("VSS class missing: "+name,e);}}
    public static Object field(Object target,String name){
        for(Class<?> c=target instanceof Class<?> k?k:target.getClass();c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(target instanceof Class<?>?null:target);}catch(NoSuchFieldException ignored){}catch(IllegalAccessException e){throw new IllegalStateException(e);}
        throw new IllegalStateException("Unsupported VSS field: "+name);
    }
    public static boolean supported(){try{return type("dev.vox.lss.common.LSSConstants").getField("PROTOCOL_VERSION").getInt(null)==20;}catch(Exception e){return false;}}
    public static UUID playerId(Object state){return (UUID)call(state,"getPlayerUUID");}
    public static ServerPlayer player(Object state){return (ServerPlayer)call(state,"getPlayer");}
    public static void enqueue(Object state,CustomPacketPayload payload,int budget,long order,int x,int z){
        try{
            Class<?> q=type("dev.vox.lss.common.processing.QueuedPayload");
            long packed=((long)x<<32)|(z&0xffffffffL);
            Object queued=q.getConstructor(Object.class,int.class,int.class,long.class,long.class).newInstance(payload,budget,budget,order,packed);
            call(state,"addReadyPayload",queued);
        }catch(ReflectiveOperationException e){throw new IllegalStateException("Unsupported VSS send queue ABI",e);}
    }
    public static Object manager(){return call(type("dev.vox.lss.networking.client.ClientNetGlue"),"getRequestManager");}
    public static boolean olderThanReceived(ResourceKey<Level> dim,int x,int z,long timestamp){
        Object m=manager();if(m==null||!dim.equals(field(m,"lastDimension")))return false;
        return cn.mctogether.vsscdn.common.ReceiptChecks.older((Long)call(m,"getColumnTimestamp",x,z),timestamp);
    }
    public static boolean reuseReceived(ResourceKey<Level> dim,int x,int z,long timestamp){
        Object m=manager();if(m==null||!dim.equals(field(m,"lastDimension")))return false;
        long packed=((long)x<<32)|(z&0xffffffffL);
        Object columns=field(m,"columns");
        long cached=(Long)call(m,"getColumnTimestamp",x,z);
        long classification=(Long)call(columns,"classify",packed);
        if(!cn.mctogether.vsscdn.common.ReceiptChecks.reusable(cached,timestamp,classification,field(m,"pendingCacheLoad")!=null))return false;
        call(m,"onColumnUpToDate",packed);
        return true;
    }
    public static void retry(Object owner,ResourceKey<Level> dim,long[] positions){
        Object m=manager();if(m==null||m!=owner||!dim.equals(field(m,"lastDimension")))return;
        Object tracker=field(m,"tracker");
        for(long position:positions)call(tracker,"removeByPosition",position);
        // Reopen through VSS, including during cache load, without changing the receipts.
        call(m,"onDirtyColumns",positions);
    }
    public static void receive(int x,int z,ResourceKey<Level> dim,long timestamp,byte source,byte codec,byte[] bytes,int rawSize){
        try{
            Class<?> c=type("dev.vox.lss.networking.payloads.VoxelColumnS2CPayload");
            Object payload=c.getConstructor(int.class,int.class,ResourceKey.class,long.class,byte.class,byte.class,byte[].class,int.class).newInstance(x,z,dim,timestamp,source,codec,bytes,rawSize);
            call(type("dev.vox.lss.networking.client.ClientNetGlue"),"onVoxelColumnFrame",payload);
        }catch(ReflectiveOperationException e){throw new IllegalStateException("Unsupported VSS receive ABI",e);}
    }
}
