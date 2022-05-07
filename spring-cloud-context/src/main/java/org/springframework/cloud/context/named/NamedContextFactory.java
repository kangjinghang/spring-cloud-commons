/*
 * Copyright 2012-2020 the original author or authors.
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

package org.springframework.cloud.context.named;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.MapPropertySource;

/**
 * Creates a set of child contexts that allows a set of Specifications to define the beans
 * in each child context.
 *
 * Ported from spring-cloud-netflix FeignClientFactory and SpringClientFactory
 *
 * @param <C> specification
 * @author Spencer Gibb
 * @author Dave Syer
 * @author Tommy Karlsson
 */
// TODO: add javadoc
public abstract class NamedContextFactory<C extends NamedContextFactory.Specification>
		implements DisposableBean, ApplicationContextAware { // 实现了 ApplicationContextAware 接口

	private final String propertySourceName;

	private final String propertyName;
	// 不管是在 ribbon 中还是在 feign 中，都是根据服务名，创建一个 spring 的子应用上下文，比如我们调用远程服务的时候，将服务名存在内存中的map中，以服务名作为key，值为 ApplicationContext 对象
	private Map<String, AnnotationConfigApplicationContext> contexts = new ConcurrentHashMap<>();
	// 比如每一个 @LoadBalancerClient 会产生一个以注解的属性 name/value 为 key，name/value + 注解的configuration 的属性作为构造参数构造的 LoadBalancerClientSpecification 作为 value
	private Map<String, C> configurations = new ConcurrentHashMap<>();

	private ApplicationContext parent;

	private Class<?> defaultConfigType;

	public NamedContextFactory(Class<?> defaultConfigType, String propertySourceName, String propertyName) {
		this.defaultConfigType = defaultConfigType;
		this.propertySourceName = propertySourceName;
		this.propertyName = propertyName;
	}

	@Override
	public void setApplicationContext(ApplicationContext parent) throws BeansException {
		this.parent = parent; // 将传进来的 ApplicationContext 作为 parent
	}

	public ApplicationContext getParent() {
		return parent;
	}

	public void setConfigurations(List<C> configurations) {
		for (C client : configurations) {
			this.configurations.put(client.getName(), client);
		}
	}

	public Set<String> getContextNames() {
		return new HashSet<>(this.contexts.keySet());
	}

	@Override
	public void destroy() {
		Collection<AnnotationConfigApplicationContext> values = this.contexts.values();
		for (AnnotationConfigApplicationContext context : values) {
			// This can fail, but it never throws an exception (you see stack traces
			// logged as WARN).
			context.close();
		}
		this.contexts.clear();
	}
	// 获取应用上下文
	protected AnnotationConfigApplicationContext getContext(String name) {
		if (!this.contexts.containsKey(name)) { // 首先判断 contexts map 中是否包含 key 为服务名
			synchronized (this.contexts) {
				if (!this.contexts.containsKey(name)) { // 如果不包含则以服务名作为 key，调用 createContext 方法创建 AnnotationConfigApplicationContext 作为值存入 contexts 中
					this.contexts.put(name, createContext(name));
				}
			}
		}
		return this.contexts.get(name); // 如果包含，则根据服务名取出对应的上下文对象
	}
	// 创建应用上下文。@LoadBalancerClient 注解属性 configuration 上的配置 通常要与 LoadBalancerClientConfiguration 类似，否则用 LoadBalancerClientConfiguration 兜底
	protected AnnotationConfigApplicationContext createContext(String name) {
		AnnotationConfigApplicationContext context;
		if (this.parent != null) {
			// jdk11 issue
			// https://github.com/spring-cloud/spring-cloud-netflix/issues/3101
			// https://github.com/spring-cloud/spring-cloud-openfeign/issues/475
			DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
			if (parent instanceof ConfigurableApplicationContext) {
				beanFactory.setBeanClassLoader(
						((ConfigurableApplicationContext) parent).getBeanFactory().getBeanClassLoader());
			}
			else {
				beanFactory.setBeanClassLoader(parent.getClassLoader());
			}
			context = new AnnotationConfigApplicationContext(beanFactory);
			context.setClassLoader(this.parent.getClassLoader());
		}
		else {
			context = new AnnotationConfigApplicationContext();
		}
		if (this.configurations.containsKey(name)) { // @LoadBalancerClient 的 value 或 name 属性，指定了服务名的配置
			for (Class<?> configuration : this.configurations.get(name).getConfiguration()) {
				context.register(configuration);
			}
		}
		for (Map.Entry<String, C> entry : this.configurations.entrySet()) {
			if (entry.getKey().startsWith("default.")) { // 通过@LoadBalancerClients 的 defaultConfiguration 属性指定了配置
				for (Class<?> configuration : entry.getValue().getConfiguration()) {
					context.register(configuration);
				}
			}
		}
		context.register(PropertyPlaceholderAutoConfiguration.class, this.defaultConfigType); // 注入 defaultConfigType 类型的bean，即注入 LoadBalancerClientConfiguration...
		context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(this.propertySourceName,
				Collections.<String, Object>singletonMap(this.propertyName, name)));
		if (this.parent != null) {
			// Uses Environment from parent as well as beans
			context.setParent(this.parent);  // 将 Spring ApplicationContext 设置成该服务名的上下文的父级
		}
		context.setDisplayName(generateDisplayName(name));
		context.refresh();
		return context;
	}

	protected String generateDisplayName(String name) {
		return this.getClass().getSimpleName() + "-" + name;
	}

	public <T> T getInstance(String name, Class<T> type) {
		AnnotationConfigApplicationContext context = getContext(name);
		try {
			return context.getBean(type);
		}
		catch (NoSuchBeanDefinitionException e) {
			// ignore
		}
		return null;
	}

	public <T> ObjectProvider<T> getLazyProvider(String name, Class<T> type) {
		return new ClientFactoryObjectProvider<>(this, name, type);
	}

	public <T> ObjectProvider<T> getProvider(String name, Class<T> type) {
		AnnotationConfigApplicationContext context = getContext(name);
		return context.getBeanProvider(type);
	}

	public <T> T getInstance(String name, Class<?> clazz, Class<?>... generics) {
		ResolvableType type = ResolvableType.forClassWithGenerics(clazz, generics);
		return getInstance(name, type);
	}
	// 查找某个 bean 实例
	@SuppressWarnings("unchecked")
	public <T> T getInstance(String name, ResolvableType type) {
		AnnotationConfigApplicationContext context = getContext(name);
		String[] beanNames = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(context, type);
		if (beanNames.length > 0) {
			for (String beanName : beanNames) {
				if (context.isTypeMatch(beanName, type)) {
					return (T) context.getBean(beanName);
				}
			}
		}
		return null;
	}

	public <T> Map<String, T> getInstances(String name, Class<T> type) {
		AnnotationConfigApplicationContext context = getContext(name);

		return BeanFactoryUtils.beansOfTypeIncludingAncestors(context, type);
	}

	/**
	 * Specification with name and configuration.
	 */
	public interface Specification {

		String getName();

		Class<?>[] getConfiguration();

	}

}
