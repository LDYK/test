/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.nacos.naming.core;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.exception.api.NacosApiException;
import com.alibaba.nacos.api.model.v2.ErrorCode;
import com.alibaba.nacos.api.naming.NamingResponseCode;
import com.alibaba.nacos.api.naming.PreservedMetadataKeys;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.api.naming.pojo.ServiceInfo;
import com.alibaba.nacos.api.naming.utils.NamingUtils;
import com.alibaba.nacos.common.utils.ConvertUtils;
import com.alibaba.nacos.common.utils.InternetAddressUtil;
import com.alibaba.nacos.naming.core.v2.ServiceManager;
import com.alibaba.nacos.naming.core.v2.client.Client;
import com.alibaba.nacos.naming.core.v2.client.ClientAttributes;
import com.alibaba.nacos.naming.core.v2.client.impl.IpPortBasedClient;
import com.alibaba.nacos.naming.core.v2.client.manager.ClientManager;
import com.alibaba.nacos.naming.core.v2.client.manager.ClientManagerDelegate;
import com.alibaba.nacos.naming.core.v2.index.ServiceStorage;
import com.alibaba.nacos.naming.core.v2.metadata.InstanceMetadata;
import com.alibaba.nacos.naming.core.v2.metadata.NamingMetadataManager;
import com.alibaba.nacos.naming.core.v2.metadata.NamingMetadataOperateService;
import com.alibaba.nacos.naming.core.v2.metadata.ServiceMetadata;
import com.alibaba.nacos.naming.core.v2.pojo.InstancePublishInfo;
import com.alibaba.nacos.naming.core.v2.pojo.Service;
import com.alibaba.nacos.naming.core.v2.service.ClientOperationService;
import com.alibaba.nacos.naming.core.v2.service.ClientOperationServiceProxy;
import com.alibaba.nacos.naming.healthcheck.HealthCheckReactor;
import com.alibaba.nacos.naming.healthcheck.RsInfo;
import com.alibaba.nacos.naming.healthcheck.heartbeat.ClientBeatProcessorV2;
import com.alibaba.nacos.naming.misc.Loggers;
import com.alibaba.nacos.naming.misc.SwitchDomain;
import com.alibaba.nacos.naming.misc.UtilsAndCommons;
import com.alibaba.nacos.naming.pojo.InstanceOperationInfo;
import com.alibaba.nacos.naming.pojo.Subscriber;
import com.alibaba.nacos.naming.pojo.instance.BeatInfoInstanceBuilder;
import com.alibaba.nacos.naming.push.UdpPushService;
import com.alibaba.nacos.naming.utils.ServiceUtil;
import com.alibaba.nacos.naming.web.ClientAttributesFilter;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Instance service.
 *
 * @author xiweng.yy
 */
@org.springframework.stereotype.Service
public class InstanceOperatorClientImpl implements InstanceOperator {

    //客户端管理器
    private final ClientManager clientManager;

    // 客户端操作服务 有2种具体实现
    // EphemeralClientOperationServiceImpl
    // PersistenceClientOperationServiceImpl
    private final ClientOperationService clientOperationService;

    //服务存储功能服务
    private final ServiceStorage serviceStorage;

    //元数据管理器
    private final NamingMetadataOperateService metadataOperateService;
    
    private final NamingMetadataManager metadataManager;

    //可以理解成全局的配置开关
    private final SwitchDomain switchDomain;

    //udp推送服务
    private final UdpPushService pushService;
    
    public InstanceOperatorClientImpl(ClientManagerDelegate clientManager,
            ClientOperationServiceProxy clientOperationService, ServiceStorage serviceStorage,
            NamingMetadataOperateService metadataOperateService, NamingMetadataManager metadataManager,
            SwitchDomain switchDomain, UdpPushService pushService) {
        this.clientManager = clientManager;
        this.clientOperationService = clientOperationService;
        this.serviceStorage = serviceStorage;
        this.metadataOperateService = metadataOperateService;
        this.metadataManager = metadataManager;
        this.switchDomain = switchDomain;
        this.pushService = pushService;
    }
    
    /**
     * This method creates {@code IpPortBasedClient} if it don't exist.
     */
    @Override
    public void registerInstance(String namespaceId, String serviceName, Instance instance) throws NacosException {
        NamingUtils.checkInstanceIsLegal(instance);
        // ephemeral为true为临时实例，false是持久化实例，默认为true
        boolean ephemeral = instance.isEphemeral();
        //生成一个客户端id：用ip和端口表示一个客户端 格式ip#port#true
        String clientId = IpPortBasedClient.getClientId(instance.toInetAddr(), ephemeral);
        // 创建ClientAttributes实例，并set到clientManager中
        createIpPortClientIfAbsent(clientId);
        // 创建Service实例
        Service service = getService(namespaceId, serviceName, ephemeral);
        // 调用ClientOperationServiceProxy的registerInstance()方法，根据isEphemeral选择对应的实现类EphemeralIpPortClientManager或者PersistentIpPortClientManager
        clientOperationService.registerInstance(service, instance, clientId);
    }
    
    @Override
    public void removeInstance(String namespaceId, String serviceName, Instance instance) {
        boolean ephemeral = instance.isEphemeral();
        // clientId格式：ip:port#ephemeral
        String clientId = IpPortBasedClient.getClientId(instance.toInetAddr(), ephemeral);
        //如果客户端管理器没有这个client 直接返回
        if (!clientManager.contains(clientId)) {
            Loggers.SRV_LOG.warn("remove instance from non-exist client: {}", clientId);
            return;
        }
        //构建服务对象
        Service service = getService(namespaceId, serviceName, ephemeral);
        //clientOprationService 这里的类型时 ClientOperationServiceProxy 代理类
        clientOperationService.deregisterInstance(service, instance, clientId);
    }
    
    @Override
    public void updateInstance(String namespaceId, String serviceName, Instance instance) throws NacosException {
        NamingUtils.checkInstanceIsLegal(instance);
        //根据命名空间 服务名 临时节点标志新建服务对象
        Service service = getService(namespaceId, serviceName, instance.isEphemeral());
        //服务管理器如果不存在该服务就报错
        if (!ServiceManager.getInstance().containSingleton(service)) {
            throw new NacosApiException(NacosException.INVALID_PARAM, ErrorCode.INSTANCE_ERROR,
                    "service not found, namespace: " + namespaceId + ", service: " + service);
        }
        //生成metadataId
        //metadataId的生成逻辑 ip:port:cluster
        String metadataId = InstancePublishInfo
                .genMetadataId(instance.getIp(), instance.getPort(), instance.getClusterName());
        //调用NamingMetadataOperateService的updateInstanceMetadata方法
        metadataOperateService.updateInstanceMetadata(service, metadataId, buildMetadata(instance));
    }
    
    private InstanceMetadata buildMetadata(Instance instance) {
        InstanceMetadata result = new InstanceMetadata();
        result.setEnabled(instance.isEnabled());
        result.setWeight(instance.getWeight());
        result.getExtendData().putAll(instance.getMetadata());
        return result;
    }
    
    @Override
    public void patchInstance(String namespaceId, String serviceName, InstancePatchObject patchObject)
            throws NacosException {
        Service service = getService(namespaceId, serviceName, true);
        Instance instance = getInstance(namespaceId, serviceName, patchObject.getCluster(), patchObject.getIp(),
                patchObject.getPort());
        String metadataId = InstancePublishInfo
                .genMetadataId(instance.getIp(), instance.getPort(), instance.getClusterName());
        Optional<InstanceMetadata> instanceMetadata = metadataManager.getInstanceMetadata(service, metadataId);
        InstanceMetadata newMetadata = instanceMetadata.map(this::cloneMetadata).orElseGet(InstanceMetadata::new);
        mergeMetadata(newMetadata, patchObject);
        metadataOperateService.updateInstanceMetadata(service, metadataId, newMetadata);
    }
    
    private InstanceMetadata cloneMetadata(InstanceMetadata instanceMetadata) {
        InstanceMetadata result = new InstanceMetadata();
        result.setExtendData(new HashMap<>(instanceMetadata.getExtendData()));
        result.setWeight(instanceMetadata.getWeight());
        result.setEnabled(instanceMetadata.isEnabled());
        return result;
    }
    
    private void mergeMetadata(InstanceMetadata newMetadata, InstancePatchObject patchObject) {
        if (null != patchObject.getMetadata()) {
            newMetadata.setExtendData(new HashMap<>(patchObject.getMetadata()));
        }
        if (null != patchObject.getEnabled()) {
            newMetadata.setEnabled(patchObject.getEnabled());
        }
        if (null != patchObject.getWeight()) {
            newMetadata.setWeight(patchObject.getWeight());
        }
    }
    
    @Override
    public ServiceInfo listInstance(String namespaceId, String serviceName, Subscriber subscriber, String cluster,
            boolean healthOnly) {
        //构建service
        Service service = getService(namespaceId, serviceName, true);
        // For adapt 1.X subscribe logic
        // 判断客户端sdk是否支持推送功能
        if (subscriber.getPort() > 0 && pushService.canEnablePush(subscriber.getAgent())) {
            String clientId = IpPortBasedClient.getClientId(subscriber.getAddrStr(), true);
            //创建客户端
            createIpPortClientIfAbsent(clientId);
            //EphemeralClientOperationServiceImpl 完成服务注册功能
            clientOperationService.subscribeService(service, subscriber, clientId);
        }
        //查询服务信息包括所有的实例列表
        ServiceInfo serviceInfo = serviceStorage.getData(service);
        //查询服务的元数据信息
        ServiceMetadata serviceMetadata = metadataManager.getServiceMetadata(service).orElse(null);
        //对实例列表根据集群名称/是否可用/是否健康/自定义过滤器/阈值保护层曾过滤
        ServiceInfo result = ServiceUtil
                .selectInstancesWithHealthyProtection(serviceInfo, serviceMetadata, cluster, healthOnly, true, subscriber.getIp());
        // adapt for v1.x sdk
        result.setName(NamingUtils.getGroupedName(result.getName(), result.getGroupName()));
        return result;
    }
    
    @Override
    public Instance getInstance(String namespaceId, String serviceName, String cluster, String ip, int port)
            throws NacosException {
        Service service = getService(namespaceId, serviceName, true);
        return getInstance0(service, cluster, ip, port);
    }
    
    private Instance getInstance0(Service service, String cluster, String ip, int port) throws NacosException {
        ServiceInfo serviceInfo = serviceStorage.getData(service);
        if (serviceInfo.getHosts().isEmpty()) {
            throw new NacosApiException(NacosException.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND,
                    "no ips found for cluster " + cluster + " in service " + service.getGroupedServiceName());
        }
        for (Instance each : serviceInfo.getHosts()) {
            if (cluster.equals(each.getClusterName()) && ip.equals(each.getIp()) && port == each.getPort()) {
                return each;
            }
        }
        throw new NacosApiException(NacosException.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "no matched ip found!");
    }
    
    @Override
    public int handleBeat(String namespaceId, String serviceName, String ip, int port, String cluster,
            RsInfo clientBeat, BeatInfoInstanceBuilder builder) throws NacosException {
        Service service = getService(namespaceId, serviceName, true);
        String clientId = IpPortBasedClient.getClientId(ip + InternetAddressUtil.IP_PORT_SPLITER + port, true);
        IpPortBasedClient client = (IpPortBasedClient) clientManager.getClient(clientId);
        if (null == client || !client.getAllPublishedService().contains(service)) {
            if (null == clientBeat) {
                return NamingResponseCode.RESOURCE_NOT_FOUND;
            }
            Instance instance = builder.setBeatInfo(clientBeat).setServiceName(serviceName).build();
            registerInstance(namespaceId, serviceName, instance);
            client = (IpPortBasedClient) clientManager.getClient(clientId);
        }
        if (!ServiceManager.getInstance().containSingleton(service)) {
            throw new NacosException(NacosException.SERVER_ERROR,
                    "service not found: " + serviceName + "@" + namespaceId);
        }
        if (null == clientBeat) {
            clientBeat = new RsInfo();
            clientBeat.setIp(ip);
            clientBeat.setPort(port);
            clientBeat.setCluster(cluster);
            clientBeat.setServiceName(serviceName);
        }
        ClientBeatProcessorV2 beatProcessor = new ClientBeatProcessorV2(namespaceId, clientBeat, client);
        HealthCheckReactor.scheduleNow(beatProcessor);
        client.setLastUpdatedTime();
        return NamingResponseCode.OK;
    }
    
    @Override
    public long getHeartBeatInterval(String namespaceId, String serviceName, String ip, int port, String cluster) {
        Service service = getService(namespaceId, serviceName, true);
        String metadataId = InstancePublishInfo.genMetadataId(ip, port, cluster);
        Optional<InstanceMetadata> metadata = metadataManager.getInstanceMetadata(service, metadataId);
        if (metadata.isPresent() && metadata.get().getExtendData()
                .containsKey(PreservedMetadataKeys.HEART_BEAT_INTERVAL)) {
            return ConvertUtils.toLong(metadata.get().getExtendData().get(PreservedMetadataKeys.HEART_BEAT_INTERVAL));
        }
        String clientId = IpPortBasedClient.getClientId(ip + InternetAddressUtil.IP_PORT_SPLITER + port, true);
        Client client = clientManager.getClient(clientId);
        InstancePublishInfo instance = null != client ? client.getInstancePublishInfo(service) : null;
        if (null != instance && instance.getExtendDatum().containsKey(PreservedMetadataKeys.HEART_BEAT_INTERVAL)) {
            return ConvertUtils.toLong(instance.getExtendDatum().get(PreservedMetadataKeys.HEART_BEAT_INTERVAL));
        }
        return switchDomain.getClientBeatInterval();
    }
    
    @Override
    public List<? extends Instance> listAllInstances(String namespaceId, String serviceName) throws NacosException {
        Service service = getService(namespaceId, serviceName, true);
        return serviceStorage.getData(service).getHosts();
    }
    
    @Override
    public List<String> batchUpdateMetadata(String namespaceId, InstanceOperationInfo instanceOperationInfo,
            Map<String, String> metadata) throws NacosException {
        boolean isEphemeral = !UtilsAndCommons.PERSIST.equals(instanceOperationInfo.getConsistencyType());
        String serviceName = instanceOperationInfo.getServiceName();
        Service service = getService(namespaceId, serviceName, isEphemeral);
        List<String> result = new LinkedList<>();
        List<Instance> needUpdateInstance = findBatchUpdateInstance(instanceOperationInfo, service);
        for (Instance each : needUpdateInstance) {
            String metadataId = InstancePublishInfo.genMetadataId(each.getIp(), each.getPort(), each.getClusterName());
            Optional<InstanceMetadata> instanceMetadata = metadataManager.getInstanceMetadata(service, metadataId);
            InstanceMetadata newMetadata = instanceMetadata.map(this::cloneMetadata).orElseGet(InstanceMetadata::new);
            newMetadata.getExtendData().putAll(metadata);
            metadataOperateService.updateInstanceMetadata(service, metadataId, newMetadata);
            result.add(each.toInetAddr() + ":" + UtilsAndCommons.LOCALHOST_SITE + ":" + each.getClusterName() + ":" + (
                    each.isEphemeral() ? UtilsAndCommons.EPHEMERAL : UtilsAndCommons.PERSIST));
        }
        return result;
    }
    
    @Override
    public List<String> batchDeleteMetadata(String namespaceId, InstanceOperationInfo instanceOperationInfo,
            Map<String, String> metadata) throws NacosException {
        boolean isEphemeral = !UtilsAndCommons.PERSIST.equals(instanceOperationInfo.getConsistencyType());
        String serviceName = instanceOperationInfo.getServiceName();
        Service service = getService(namespaceId, serviceName, isEphemeral);
        List<String> result = new LinkedList<>();
        List<Instance> needUpdateInstance = findBatchUpdateInstance(instanceOperationInfo, service);
        for (Instance each : needUpdateInstance) {
            String metadataId = InstancePublishInfo.genMetadataId(each.getIp(), each.getPort(), each.getClusterName());
            Optional<InstanceMetadata> instanceMetadata = metadataManager.getInstanceMetadata(service, metadataId);
            InstanceMetadata newMetadata = instanceMetadata.map(this::cloneMetadata).orElseGet(InstanceMetadata::new);
            metadata.keySet().forEach(key -> newMetadata.getExtendData().remove(key));
            metadataOperateService.updateInstanceMetadata(service, metadataId, newMetadata);
            result.add(each.toInetAddr() + ":" + UtilsAndCommons.LOCALHOST_SITE + ":" + each.getClusterName() + ":" + (
                    each.isEphemeral() ? UtilsAndCommons.EPHEMERAL : UtilsAndCommons.PERSIST));
        }
        return result;
    }
    
    private List<Instance> findBatchUpdateInstance(InstanceOperationInfo instanceOperationInfo, Service service) {
        if (null == instanceOperationInfo.getInstances() || instanceOperationInfo.getInstances().isEmpty()) {
            return serviceStorage.getData(service).getHosts();
        }
        List<Instance> result = new LinkedList<>();
        for (Instance each : instanceOperationInfo.getInstances()) {
            try {
                getInstance0(service, each.getClusterName(), each.getIp(), each.getPort());
                result.add(each);
            } catch (NacosException ignored) {
            }
        }
        return result;
    }
    
    private void createIpPortClientIfAbsent(String clientId) {
        if (!clientManager.contains(clientId)) {
            ClientAttributes clientAttributes;
            if (ClientAttributesFilter.threadLocalClientAttributes.get() != null) {
                clientAttributes = ClientAttributesFilter.threadLocalClientAttributes.get();
            } else {
                clientAttributes = new ClientAttributes();
            }
            // 调用ClientManagerDelegate的clientConnected()方法
            clientManager.clientConnected(clientId, clientAttributes);
        }
    }
    
    private Service getService(String namespaceId, String serviceName, boolean ephemeral) {
        String groupName = NamingUtils.getGroupName(serviceName);
        String serviceNameNoGrouped = NamingUtils.getServiceName(serviceName);
        return Service.newService(namespaceId, groupName, serviceNameNoGrouped, ephemeral);
    }
    
}
