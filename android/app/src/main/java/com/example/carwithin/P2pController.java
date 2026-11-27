package com.example.carwithin;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.*;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.*;
import android.os.*;
import java.net.*;
import java.util.*;

/** All manager callbacks and state live on the main thread. Never expose a route before owner validation. */
@SuppressLint("MissingPermission")
public final class P2pController {
    public record Route(Target target, String iface, Inet4Address local, Inet4Address remote, Network network) {}
    public interface Listener {
        void log(String message);
        void peers(List<WifiP2pDevice> peers);
        void route(Route route);
        void connectionStatus(String message, boolean connecting);
    }
    private final Context context;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private Target target;
    private Route route;
    private int generation, verification;
    private boolean closed, connecting, awaitingGroup;
    private final BroadcastReceiver receiver=new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a=i.getAction();
            if(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(a)) {
                boolean enabled=i.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE,-1)==WifiP2pManager.WIFI_P2P_STATE_ENABLED;
                listener.log(enabled?"P2P 可用":"P2P 未启用，请检查 Wi-Fi 和系统定位开关");
                if(!enabled)fail("请开启 Wi-Fi 后重试");
            } else if(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(a))refreshPeers();
            else if(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(a)) {
                NetworkInfo info=i.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                if(info!=null && !info.isConnected()) {
                    if(route!=null)fail("P2P 链路已断开");
                }
                else verify(generation);
            }
        }
    };
    public P2pController(Context context,Listener listener) {
        this.context=context;this.listener=listener;
        manager=(WifiP2pManager)context.getSystemService(Context.WIFI_P2P_SERVICE);
        if(manager!=null)channel=manager.initialize(context,Looper.getMainLooper(),()->{
            channel=null;fail("P2P 系统通道已关闭，请重新打开 App");
        });
        IntentFilter f=new IntentFilter();
        f.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(receiver,f);
    }
    private boolean ready() {
        if(closed||manager==null||channel==null){listener.log("此设备的 P2P 服务不可用");return false;}
        String permission=Build.VERSION.SDK_INT>=33?Manifest.permission.NEARBY_WIFI_DEVICES:Manifest.permission.ACCESS_FINE_LOCATION;
        if(context.checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED){listener.log("请先授予附近 Wi-Fi / 定位权限");return false;}
        return true;
    }
    public void scan() {
        if(connecting || !ready())return;
        if(route==null)listener.connectionStatus("正在扫描 P2P 设备…",false);
        manager.discoverPeers(channel,new WifiP2pManager.ActionListener() {
            @Override public void onSuccess(){refreshPeers();}
            @Override public void onFailure(int reason){
                String message="P2P 扫描失败（"+reason+"），请检查 Wi-Fi 和定位开关";
                listener.log(message);
                if(!connecting && route==null)listener.connectionStatus(message,false);
            }
        });
    }

    private void refreshPeers() {
        if(ready())manager.requestPeers(channel,list->{
            if(closed)return;
            listener.peers(new ArrayList<>(list.getDeviceList()));
            if(!connecting && route==null)listener.connectionStatus(
                    list.getDeviceList().isEmpty()?"正在查找设备，请确认仪表已开启投屏":"请选择要连接的仪表",false);
        });
    }

    public void connect(Target wanted, boolean automatic) {
        if(!ready() || connecting)return;
        if(route!=null && route.target().equals(wanted))return;
        int gen=++generation;
        target=wanted;
        invalidate();
        connecting=true;
        awaitingGroup=false;
        updateStatus(automatic?"正在尝试重连上次的仪表…":"正在查找所选仪表…");
        manager.requestGroupInfo(channel,group->{
            if(!current(gen))return;
            if(group==null) {
                findTarget(gen, SystemClock.elapsedRealtime()+10000, true);
            } else if(matchesGroup(group)) {
                awaitingGroup=true;
                verify(gen);
            } else if(automatic) {
                fail("已有其他 P2P 连接，请手动扫描并选择仪表");
            } else {
                manager.removeGroup(channel,action("清理旧组",()->findTarget(
                        gen, SystemClock.elapsedRealtime()+10000, true),gen));
            }
        });
        main.postDelayed(()->{
            if(!current(gen) || !connecting)return;
            fail("P2P 连接超时，请检查仪表投屏模式后重试");
            if(ready())manager.cancelConnect(channel,null);
        },30000);
    }

    private void findTarget(int gen, long deadline, boolean startDiscovery) {
        if(!current(gen))return;
        manager.requestPeers(channel,list->{
            if(!current(gen))return;
            for(WifiP2pDevice peer:list.getDeviceList()) {
                if(target.matches(peer.deviceName,peer.deviceAddress)) {
                    startConnect(gen,peer.deviceAddress);
                    return;
                }
            }
            if(SystemClock.elapsedRealtime()>=deadline) {
                fail("未找到所选仪表，请开启仪表投屏后重试");
                return;
            }
            if(startDiscovery)manager.discoverPeers(channel,action("扫描仪表",()->{},gen));
            main.postDelayed(()->findTarget(gen,deadline,false),800);
        });
    }

    private void startConnect(int gen, String address) {
        WifiP2pConfig config=new WifiP2pConfig();
        config.deviceAddress=address;
        config.wps.setup=WpsInfo.PBC;
        config.groupOwnerIntent=0;
        updateStatus("正在连接 "+target.name()+"，请留意系统提示");
        manager.connect(channel,config,action("连接请求",()->{
            awaitingGroup=true;
            verify(gen);
        },gen));
    }

    private boolean matchesGroup(WifiP2pGroup group) {
        WifiP2pDevice owner=group.getOwner();
        return !group.isGroupOwner() && owner!=null &&
                target.matchesOwner(owner.deviceName,group.getNetworkName(),owner.deviceAddress);
    }

    private void updateStatus(String message) {
        listener.connectionStatus(message,connecting);
        listener.log(message);
    }

    private void fail(String message) {
        generation++;
        target=null;
        connecting=false;
        awaitingGroup=false;
        invalidate();
        updateStatus(message);
    }

    private boolean current(int gen){return !closed&&generation==gen;}
    private void verify(int gen) {
        if(!awaitingGroup || target==null || !ready())return;
        int check=++verification;
        manager.requestConnectionInfo(channel,info->{
            if(!current(gen)||verification!=check)return;
            if(!info.groupFormed) {
                if(route!=null)fail("P2P 链路已断开");
                else retryVerification(gen,check);
                return;
            }
            manager.requestGroupInfo(channel,group->{
                if(!current(gen)||verification!=check)return;
                if(group==null) {
                    retryVerification(gen,check);
                    return;
                }
                if(info.isGroupOwner||!matchesGroup(group)) {
                    fail("P2P 组主身份与所选仪表不符，连接未通过核对");
                    return;
                }
                try {
                    if(!(info.groupOwnerAddress instanceof Inet4Address))throw new IllegalStateException("未获得仪表 IPv4");
                    String iface=group.getInterface();NetworkInterface ni=NetworkInterface.getByName(iface);
                    if(ni==null)throw new IllegalStateException("P2P 接口尚未就绪");
                    Inet4Address local=null;
                    for(InetAddress ip:Collections.list(ni.getInetAddresses()))if(ip instanceof Inet4Address&&!ip.isLoopbackAddress())local=(Inet4Address)ip;
                    if(local==null) {retryVerification(gen,check);return;}
                    ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
                    Network network=null;
                    for(Network n:cm.getAllNetworks()) {
                        LinkProperties lp=cm.getLinkProperties(n);
                        if(lp!=null && iface.equals(lp.getInterfaceName()))network=n;
                    }
                    Route next=new Route(target,iface,local,(Inet4Address)info.groupOwnerAddress,network);
                    connecting=false;
                    if(route!=null && route.equals(next))return;
                    if(route!=null)invalidate();route=next;
                    listener.log("身份已核对："+target.mac()+"，"+iface+" "+local.getHostAddress()+" → "+next.remote().getHostAddress());
                    listener.route(next);
                } catch(Exception e){
                    if(connecting)retryVerification(gen,check);
                    else fail("P2P 路由不可用："+e.getMessage());
                }
            });
        });
    }
    private void retryVerification(int gen, int check) {
        if(connecting)main.postDelayed(()->{if(current(gen)&&verification==check)verify(gen);},500);
    }
    private WifiP2pManager.ActionListener action(String name,Runnable success,int gen) {
        return new WifiP2pManager.ActionListener() {
            @Override public void onSuccess(){if(current(gen))success.run();}
            @Override public void onFailure(int reason){if(current(gen))fail(name+"失败（"+reason+"），请检查 Wi-Fi 和仪表状态");}
        };
    }
    private void invalidate(){verification++;route=null;listener.route(null);}
    public void disconnect() {
        fail("已断开 P2P");
        if(ready()){
            manager.cancelConnect(channel,null);
            manager.requestGroupInfo(channel,group->{
                if(target==null && group!=null)manager.removeGroup(channel,null);
            });
        }
    }
    public void close() {
        generation++;closed=true;main.removeCallbacksAndMessages(null);invalidate();context.unregisterReceiver(receiver);
        if(channel!=null) {if(Build.VERSION.SDK_INT>=27)channel.close();channel=null;}
    }
}
