/*
 * Copyright 2024-2025 the original author or authors.
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

package com.alibaba.cloud.ai.mcp.dynamic.server.watcher;

import com.alibaba.cloud.ai.mcp.dynamic.server.definition.DynamicNacosToolDefinition;
import com.alibaba.cloud.ai.mcp.dynamic.server.provider.DynamicMcpToolsProvider;
import com.alibaba.cloud.ai.mcp.dynamic.server.tools.DynamicNacosToolsInfo;
import com.alibaba.cloud.ai.mcp.dynamic.server.tools.NacosHelper;
import com.alibaba.cloud.ai.mcp.nacos.common.NacosMcpRegistryProperties;
import com.alibaba.nacos.api.config.ConfigChangeEvent;
import com.alibaba.nacos.api.config.ConfigChangeItem;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.Event;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.client.config.listener.impl.AbstractConfigChangeListener;
import com.alibaba.nacos.common.utils.CollectionUtils;
import com.alibaba.nacos.common.utils.JacksonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class DynamicNacosToolsWatcher extends AbstractConfigChangeListener implements EventListener {

	private static final Logger logger = LoggerFactory.getLogger(DynamicNacosToolsWatcher.class);

	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

	private static final long POLLING_INTERVAL = 30L; // 轮询间隔，单位秒

	private static final String toolsConfigSuffix = "-mcp-tools.json";

	private final NamingService namingService;

	private final ConfigService configService;

	private final NacosMcpRegistryProperties nacosMcpRegistryProperties;

	private final DynamicMcpToolsProvider dynamicMcpToolsProvider;

	private final WebClient webClient;

	// 缓存服务名称和其工具的映射关系
	private final Map<String, Set<String>> serviceToolsCache = new ConcurrentHashMap<>();

	private volatile String nacosVersion;

	public DynamicNacosToolsWatcher(final NamingService namingService, final ConfigService configService,
			final NacosMcpRegistryProperties nacosMcpRegistryProperties,
			final DynamicMcpToolsProvider dynamicMcpToolsProvider, final WebClient webClient) {
		this.namingService = namingService;
		this.configService = configService;
		this.nacosMcpRegistryProperties = nacosMcpRegistryProperties;
		this.dynamicMcpToolsProvider = dynamicMcpToolsProvider;
		this.webClient = webClient;
		this.nacosVersion = NacosHelper.fetchNacosVersion(webClient, nacosMcpRegistryProperties.getServerAddr());
		logger.info("Fetched nacos server version at startup: {}", nacosVersion);
		// 启动定时任务
		this.startScheduledPolling();
	}

	private void startScheduledPolling() {
		scheduler.scheduleAtFixedRate(this::watch, POLLING_INTERVAL, POLLING_INTERVAL, TimeUnit.SECONDS);
		logger.info("Started scheduled service polling with interval: {} seconds", POLLING_INTERVAL);
	}

	public void stop() {
		scheduler.shutdown();
		try {
			if (!scheduler.awaitTermination(60, TimeUnit.SECONDS)) {
				scheduler.shutdownNow();
			}
		}
		catch (InterruptedException e) {
			scheduler.shutdownNow();
			Thread.currentThread().interrupt();
		}
		logger.info("Stopped scheduled service polling");
	}

	private String getNacosVersion() {
		if (nacosVersion == null) {
			nacosVersion = NacosHelper.fetchNacosVersion(webClient, nacosMcpRegistryProperties.getServerAddr());
			logger.info("Fetched nacos server version on demand: {}", nacosVersion);
		}
		return nacosVersion;
	}

	private void watch() {
		String version = getNacosVersion();
		logger.info("Nacos server version: {}", version);
		if (version != null && NacosHelper.compareVersion(version, "3.0.0") >= 0) {
			logger.info("Nacos version >= 3.0.0, use new logic (not implemented yet)");
			return;
		}
		Set<String> currentServices = new HashSet<>();
		try {
			List<String> allServices = NacosHelper.listAllServices(namingService,
					nacosMcpRegistryProperties.getServiceGroup());
			currentServices.addAll(allServices);
			for (String serviceName : allServices) {
				updateServiceTools(serviceName);
				namingService.subscribe(serviceName, nacosMcpRegistryProperties.getServiceGroup(), this);
				configService.addListener(serviceName, nacosMcpRegistryProperties.getServiceGroup(), this);
			}
			cleanupStaleServices(currentServices);
		}
		catch (NacosException e) {
			logger.error("Failed to poll services list", e);
		}
		catch (Exception e) {
			logger.error("Unexpected error during service polling", e);
		}
	}

	private void cleanupStaleServices(Set<String> currentServices) {
		// 获取所有已缓存但不在当前服务列表中的服务
		Set<String> staleServices = new HashSet<>(serviceToolsCache.keySet());
		staleServices.removeAll(currentServices);

		// 移除过期服务的所有工具
		for (String staleService : staleServices) {
			Set<String> toolsToRemove = serviceToolsCache.get(staleService);
			if (toolsToRemove != null) {
				for (String toolName : toolsToRemove) {
					try {
						logger.info("Removing tool: {} for stale service: {}", toolName, staleService);
						dynamicMcpToolsProvider.removeTool(toolName);
					}
					catch (Exception e) {
						logger.error("Failed to remove tool: {} for service: {}", toolName, staleService, e);
					}
				}
			}
			serviceToolsCache.remove(staleService);
		}
	}

	private void updateServiceTools(String serviceName) {
		try {
			String toolConfig = configService.getConfig(serviceName + toolsConfigSuffix,
					nacosMcpRegistryProperties.getServiceGroup(), 5000);

			// 获取该服务当前的实例列表
			List<Instance> instances = namingService.getAllInstances(serviceName,
					nacosMcpRegistryProperties.getServiceGroup());

			// 检查是否有健康且启用的实例
			boolean hasHealthyEnabledInstance = NacosHelper.hasHealthyEnabledInstance(instances);

			// 如果没有实例、没有健康且启用的实例或配置为空，移除所有相关工具
			if (CollectionUtils.isEmpty(instances) || !hasHealthyEnabledInstance || toolConfig == null) {
				logger.info("Service {} has no healthy and enabled instances or no tool config, removing all tools",
						serviceName);
				removeServiceTools(serviceName);
				return;
			}

			// 解析工具配置
			DynamicNacosToolsInfo toolsInfo = JacksonUtils.toObj(toolConfig, DynamicNacosToolsInfo.class);
			List<DynamicNacosToolDefinition> toolsInNacos = toolsInfo.getTools();

			if (CollectionUtils.isEmpty(toolsInNacos)) {
				removeServiceTools(serviceName);
				return;
			}

			// 更新工具缓存
			Set<String> currentTools = new HashSet<>();
			for (DynamicNacosToolDefinition toolDefinition : toolsInNacos) {
				currentTools.add(toolDefinition.name());
				toolDefinition.setServiceName(serviceName);
				dynamicMcpToolsProvider.addTool(toolDefinition);
			}

			// 获取之前的工具集合
			Set<String> previousTools = serviceToolsCache.getOrDefault(serviceName, new HashSet<>());

			// 移除不再存在的工具
			Set<String> toolsToRemove = new HashSet<>(previousTools);
			toolsToRemove.removeAll(currentTools);
			for (String toolName : toolsToRemove) {
				logger.info("Removing obsolete tool: {} for service: {}", toolName, serviceName);
				dynamicMcpToolsProvider.removeTool(toolName);
			}

			// 更新缓存
			serviceToolsCache.put(serviceName, currentTools);

		}
		catch (NacosException e) {
			logger.error("Failed to update tools for service: {}", serviceName, e);
		}
		catch (Exception e) {
			logger.error("Unexpected error while updating tools for service: {}", serviceName, e);
		}
	}

	private void removeServiceTools(String serviceName) {
		Set<String> tools = serviceToolsCache.remove(serviceName);
		if (tools != null) {
			for (String toolName : tools) {
				try {
					logger.info("Removing tool: {} for service: {}", toolName, serviceName);
					dynamicMcpToolsProvider.removeTool(toolName);
				}
				catch (Exception e) {
					logger.error("Failed to remove tool: {} for service: {}", toolName, serviceName, e);
				}
			}
		}
	}

	@Override
	public void onEvent(Event event) {
		if (event instanceof NamingEvent namingEvent) {
			String serviceName = namingEvent.getServiceName();
			logger.info("Received service instance change event for service: {}", serviceName);
			updateServiceTools(serviceName);
		}
	}

	@Override
	public void receiveConfigChange(final ConfigChangeEvent event) {
		for (ConfigChangeItem item : event.getChangeItems()) {
			String dataId = item.getKey();
			if (dataId != null && dataId.endsWith(toolsConfigSuffix)) {
				String serviceName = dataId.substring(0, dataId.length() - toolsConfigSuffix.length());
				logger.info("Received config change event for service: {}", serviceName);
				updateServiceTools(serviceName);
			}
		}
	}

}
