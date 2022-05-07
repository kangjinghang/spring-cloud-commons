/*
 * Copyright 2012-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.client.loadbalancer;

import java.util.List;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Default {@link LoadBalancerRequest} implementation.
 *
 * @author Olga Maciaszek-Sharma
 * @since 3.1.2
 */
class BlockingLoadBalancerRequest implements HttpRequestLoadBalancerRequest<ClientHttpResponse> {

	private final LoadBalancerClient loadBalancer;

	private final List<LoadBalancerRequestTransformer> transformers;

	private final ClientHttpRequestData clientHttpRequestData;

	BlockingLoadBalancerRequest(LoadBalancerClient loadBalancer, List<LoadBalancerRequestTransformer> transformers,
			ClientHttpRequestData clientHttpRequestData) {
		this.loadBalancer = loadBalancer;
		this.transformers = transformers;
		this.clientHttpRequestData = clientHttpRequestData;
	}
	// ServiceRequestWrapper 继承了 spring-web 的 HttpRequestWrapper，包装了 HttpRequest，重写了 getURI 方法，会将 uri 中的服务名改写成 ip+port 形式
	@Override
	public ClientHttpResponse apply(ServiceInstance instance) throws Exception {
		HttpRequest serviceRequest = new ServiceRequestWrapper(clientHttpRequestData.request, instance, loadBalancer); // 创建 ServiceRequestWrapper，将 HttpRequest、ServiceInstance 还有 LoadBalancerClient 对象进行了包装
		if (this.transformers != null) { // 判断 transformers 列表是否等于null
			for (LoadBalancerRequestTransformer transformer : this.transformers) { // 迭代 transformers 列表并调用 LoadBalancerRequestTransformer 的 transformRequest 方法
				serviceRequest = transformer.transformRequest(serviceRequest, instance); // 如果想在获取可用服务实例之后，发起远程调用之前，比如想改变目标服务，那么就可以自己实现 LoadBalancerRequestTransformer 接口，重写 transformRequest 方法
			}
		}
		return clientHttpRequestData.execution.execute(serviceRequest, clientHttpRequestData.body); // 调用 ClientHttpRequestExecution 的 execute 方法，ClientHttpRequestExecution 接口只有一个实现，就是 InterceptingRequestExecution
	}

	@Override
	public HttpRequest getHttpRequest() {
		return clientHttpRequestData.request;
	}

	static class ClientHttpRequestData {

		private final HttpRequest request;

		private final byte[] body;

		private final ClientHttpRequestExecution execution;

		ClientHttpRequestData(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) {
			this.request = request;
			this.body = body;
			this.execution = execution;
		}

	}

}
