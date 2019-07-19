package com.baomidou.jobs.rpc.remoting.provider;

import com.baomidou.jobs.rpc.registry.ServiceRegistry;
import com.baomidou.jobs.rpc.remoting.net.NetEnum;
import com.baomidou.jobs.rpc.remoting.net.Server;
import com.baomidou.jobs.rpc.remoting.net.params.BaseCallback;
import com.baomidou.jobs.rpc.remoting.net.params.JobsRpcRequest;
import com.baomidou.jobs.rpc.remoting.net.params.JobsRpcResponse;
import com.baomidou.jobs.rpc.serialize.Serializer;
import com.baomidou.jobs.rpc.util.IpUtil;
import com.baomidou.jobs.exception.JobsRpcException;
import com.baomidou.jobs.rpc.util.NetUtil;
import com.baomidou.jobs.rpc.util.ThrowableUtil;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * provider
 *
 * @author xuxueli 2015-10-31 22:54:27
 */
@Slf4j
public class JobsRpcProviderFactory {
	private NetEnum netType;
	private Serializer serializer;
	private String ip;
	private int port;
	private String accessToken;

	private Class<? extends ServiceRegistry> serviceRegistryClass;
	private Map<String, String> serviceRegistryParam;


	public JobsRpcProviderFactory() {
		// to do nothing
	}

	public void initConfig(NetEnum netType,
						  Serializer serializer,
						  String ip,
						  int port,
						  String accessToken,
						   Class<? extends ServiceRegistry> serviceRegistryClass,
						  Map<String, String> serviceRegistryParam) {

		// init
		this.netType = netType;
		this.serializer = serializer;
		this.ip = ip;
		this.port = port;
		this.accessToken = accessToken;
		this.serviceRegistryClass = serviceRegistryClass;
		this.serviceRegistryParam = serviceRegistryParam;

		// valid
		if (this.netType==null) {
			throw new JobsRpcException("Jobs rpc provider netType missing.");
		}
		if (this.serializer==null) {
			throw new JobsRpcException("Jobs rpc provider serializer missing.");
		}
		if (this.ip == null) {
			this.ip = IpUtil.getIp();
		}
		if (this.port <= 0) {
			this.port = 7080;
		}
		if (NetUtil.isPortUsed(this.port)) {
			throw new JobsRpcException("Jobs rpc provider port["+ this.port +"] is used.");
		}
		if (this.serviceRegistryClass != null) {
			if (this.serviceRegistryParam == null) {
				throw new JobsRpcException("Jobs rpc provider serviceRegistryParam is missing.");
			}
		}

	}


	public Serializer getSerializer() {
		return serializer;
	}

	public int getPort() {
		return port;
	}


	// ---------------------- start / stop ----------------------

	private Server server;
	private ServiceRegistry serviceRegistry;
	private String serviceAddress;

	public void start() throws Exception {
		// start server
		serviceAddress = IpUtil.getIpPort(this.ip, port);
		server = netType.serverClass.newInstance();
		server.setStartedCallback(new BaseCallback() {
			// serviceRegistry started
			@Override
			public void run() throws Exception {
				// start registry
				if (serviceRegistryClass != null) {
					serviceRegistry = serviceRegistryClass.newInstance();
					serviceRegistry.start(serviceRegistryParam);
					if (serviceData.size() > 0) {
						serviceRegistry.registry(serviceData.keySet(), serviceAddress);
					}
				}
			}
		});
		server.setStopedCallback(new BaseCallback() {
			// serviceRegistry stoped
			@Override
			public void run() {
				// stop registry
				if (serviceRegistry != null) {
					if (serviceData.size() > 0) {
						serviceRegistry.remove(serviceData.keySet(), serviceAddress);
					}
					serviceRegistry.stop();
					serviceRegistry = null;
				}
			}
		});
		server.start(this);
	}

	public void  stop() throws Exception {
		// stop server
		server.stop();
	}


	// ---------------------- server invoke ----------------------

	/**
	 * init local rpc service map
	 */
	private Map<String, Object> serviceData = new HashMap<>();
	public Map<String, Object> getServiceData() {
		return serviceData;
	}

	/**
	 * make service key
	 *
	 * @param iface
	 * @param version
	 * @return
	 */
	public static String makeServiceKey(String iface, String version){
		String serviceKey = iface;
		if (version!=null && version.trim().length()>0) {
			serviceKey += "#".concat(version);
		}
		return serviceKey;
	}

	/**
	 * add service
	 *
	 * @param iface
	 * @param version
	 * @param serviceBean
	 */
	public void addService(String iface, String version, Object serviceBean){
		String serviceKey = makeServiceKey(iface, version);
		serviceData.put(serviceKey, serviceBean);
		log.info("Jobs rpc, provider factory add service success. serviceKey = {}, serviceBean = {}", serviceKey, serviceBean.getClass());
	}

	/**
	 * invoke service
	 *
	 * @param xxlRpcRequest
	 * @return
	 */
	public JobsRpcResponse invokeService(JobsRpcRequest xxlRpcRequest) {

		//  make response
		JobsRpcResponse xxlRpcResponse = new JobsRpcResponse();
		xxlRpcResponse.setRequestId(xxlRpcRequest.getRequestId());

		// match service bean
		String serviceKey = makeServiceKey(xxlRpcRequest.getClassName(), xxlRpcRequest.getVersion());
		Object serviceBean = serviceData.get(serviceKey);

		// valid
		if (serviceBean == null) {
			xxlRpcResponse.setErrorMsg("The serviceKey["+ serviceKey +"] not found.");
			return xxlRpcResponse;
		}

		if (System.currentTimeMillis() - xxlRpcRequest.getCreateMillisTime() > 3*60*1000) {
			xxlRpcResponse.setErrorMsg("The timestamp difference between admin and executor exceeds the limit.");
			return xxlRpcResponse;
		}
		if (accessToken!=null && accessToken.trim().length()>0 && !accessToken.trim().equals(xxlRpcRequest.getAccessToken())) {
			xxlRpcResponse.setErrorMsg("The access token[" + xxlRpcRequest.getAccessToken() + "] is wrong.");
			return xxlRpcResponse;
		}

		try {
			// invoke
			Class<?> serviceClass = serviceBean.getClass();
			String methodName = xxlRpcRequest.getMethodName();
			Class<?>[] parameterTypes = xxlRpcRequest.getParameterTypes();
			Object[] parameters = xxlRpcRequest.getParameters();

            Method method = serviceClass.getMethod(methodName, parameterTypes);
            method.setAccessible(true);
			Object result = method.invoke(serviceBean, parameters);

			/*FastClass serviceFastClass = FastClass.create(serviceClass);
			FastMethod serviceFastMethod = serviceFastClass.getMethod(methodName, parameterTypes);
			Object result = serviceFastMethod.invoke(serviceBean, parameters);*/

			xxlRpcResponse.setResult(result);
		} catch (Throwable t) {
			log.error("Jobs rpc provider invokeService error.", t);
			xxlRpcResponse.setErrorMsg(ThrowableUtil.toString(t));
		}

		return xxlRpcResponse;
	}

}
