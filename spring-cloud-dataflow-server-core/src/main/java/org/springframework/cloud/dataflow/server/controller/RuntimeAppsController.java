/*
 * Copyright 2015-2017 the original author or authors.
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

package org.springframework.cloud.dataflow.server.controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Stream;

import org.springframework.cloud.dataflow.core.StreamDefinition;
import org.springframework.cloud.dataflow.core.StreamDeployment;
import org.springframework.cloud.dataflow.rest.resource.AppInstanceStatusResource;
import org.springframework.cloud.dataflow.rest.resource.AppStatusResource;
import org.springframework.cloud.dataflow.server.controller.support.ApplicationsMetrics;
import org.springframework.cloud.dataflow.server.controller.support.ControllerUtils;
import org.springframework.cloud.dataflow.server.controller.support.MetricStore;
import org.springframework.cloud.dataflow.server.repository.DeploymentIdRepository;
import org.springframework.cloud.dataflow.server.repository.DeploymentKey;
import org.springframework.cloud.dataflow.server.repository.StreamDefinitionRepository;
import org.springframework.cloud.dataflow.server.repository.StreamDeploymentRepository;
import org.springframework.cloud.dataflow.server.stream.SkipperStreamDeployer;
import org.springframework.cloud.dataflow.server.stream.StreamDeployers;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppInstanceStatus;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.app.DeploymentState;
import org.springframework.cloud.skipper.client.SkipperClient;
import org.springframework.cloud.skipper.domain.Info;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.hateoas.ExposesResourceFor;
import org.springframework.hateoas.PagedResources;
import org.springframework.hateoas.ResourceAssembler;
import org.springframework.hateoas.Resources;
import org.springframework.hateoas.mvc.ResourceAssemblerSupport;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static java.util.stream.Collectors.toList;

/**
 * Exposes runtime status of deployed apps.
 *
 * @author Eric Bottard
 * @author Mark Fisher
 * @author Janne Valkealahti
 * @author Ilayaperumal Gopinathan
 * @author Gunnar Hillert
 */
@RestController
@RequestMapping("/runtime/apps")
@ExposesResourceFor(AppStatusResource.class)
public class RuntimeAppsController {

	private static final Comparator<? super AppInstanceStatus> INSTANCE_SORTER = new Comparator<AppInstanceStatus>() {
		@Override
		public int compare(AppInstanceStatus i1, AppInstanceStatus i2) {
			return i1.getId().compareTo(i2.getId());
		}
	};

	/**
	 * The repository this controller will use for stream CRUD operations.
	 */
	private final StreamDefinitionRepository streamDefinitionRepository;

	private final StreamDeploymentRepository streamDeploymentRepository;

	/**
	 * The repository this controller will use for deployment IDs.
	 */
	private final DeploymentIdRepository deploymentIdRepository;

	/**
	 * The deployer this controller will use to deploy stream apps.
	 */
	private final AppDeployer appDeployer;

	private final ResourceAssembler<AppStatus, AppStatusResource> statusAssembler = new Assembler();

	private final MetricStore metricStore;

	private final ForkJoinPool forkJoinPool;

	private final SkipperClient skipperClient;

	/**
	 * Instantiates a new runtime apps controller.
	 *
	 * @param streamDefinitionRepository the repository this controller will use for stream CRUD operations
	 * @param streamDeploymentRepository the repository to use to retrieve stream deployments
	 * @param deploymentIdRepository the repository this controller will use for deployment IDs
	 * @param appDeployer the deployer this controller will use to get the status of deployed stream apps
	 * @param metricStore the proxy to the metrics collector
	 * @param forkJoinPool a ForkJoinPool which will be used to query AppStatuses in parallel
	 * @param skipperClient the Skipper client to get the status from Skipper server
	 */
	public RuntimeAppsController(StreamDefinitionRepository streamDefinitionRepository,
			StreamDeploymentRepository streamDeploymentRepository,
			DeploymentIdRepository deploymentIdRepository, AppDeployer appDeployer, MetricStore metricStore,
			ForkJoinPool forkJoinPool, SkipperClient skipperClient) {
		Assert.notNull(streamDefinitionRepository, "StreamDefinitionRepository must not be null");
		Assert.notNull(streamDeploymentRepository, "StreamDeploymentRepository must not be null");
		Assert.notNull(deploymentIdRepository, "DeploymentIdRepository must not be null");
		Assert.notNull(appDeployer, "AppDeployer must not be null");
		Assert.notNull(metricStore, "MetricStore must not be null");
		Assert.notNull(forkJoinPool, "ForkJoinPool must not be null");
		Assert.notNull(skipperClient, "SkipperClient must not be null");
		this.streamDefinitionRepository = streamDefinitionRepository;
		this.streamDeploymentRepository = streamDeploymentRepository;
		this.deploymentIdRepository = deploymentIdRepository;
		this.appDeployer = appDeployer;
		this.metricStore = metricStore;
		this.forkJoinPool = forkJoinPool;
		this.skipperClient = skipperClient;
	}

	@RequestMapping
	public PagedResources<AppStatusResource> list(Pageable pageable, PagedResourcesAssembler<AppStatus> assembler)
			throws ExecutionException, InterruptedException {

		List<String> appDeployerStreams = new ArrayList<>();
		List<String> skipperStreams = new ArrayList<>();
		List<StreamDefinition> appDeployerStreamDefinitions = new ArrayList<>();
		Iterable<StreamDefinition> streamDefinitions = this.streamDefinitionRepository.findAll();
		Iterable<StreamDeployment> streamDeployments = this.streamDeploymentRepository.findAll();
		long deploymentSize = 0;
		for (StreamDeployment streamDeployment: streamDeployments) {
			deploymentSize++;
			if (streamDeployment.getDeployerName().equals(StreamDeployers.skipper.name())) {
				skipperStreams.add(streamDeployment.getStreamName());
			}
			else if (streamDeployment.getDeployerName().equals(StreamDeployers.appdeployer.name())) {
				appDeployerStreams.add(streamDeployment.getStreamName());
			}
		}
		for (StreamDefinition streamDefinition : streamDefinitions) {
			if (appDeployerStreams.contains(streamDefinition.getName())) {
				appDeployerStreamDefinitions.add(streamDefinition);
			}
		}
		List<AppStatus> statuses = new ArrayList<>();
		statuses.addAll(getAppDeployerStatuses(pageable, appDeployerStreamDefinitions));
		statuses.addAll(getSkipperStatuses(pageable, skipperStreams)
				.stream().flatMap(List::stream).collect(toList()));
		enrichWithMetrics(statuses);
		// finally, pass in pageable and tell how many items we have in all pages
		return assembler.toResource(new PageImpl<>(statuses, pageable, deploymentSize), statusAssembler);
	}

	private List<AppStatus> getAppDeployerStatuses(Pageable pageable,
			List<StreamDefinition> appDeployerStreamDefinitions) throws ExecutionException, InterruptedException  {
		// First build a sorted list of deployment id's so that we have
		// a predictable paging order.
		List<String> deploymentIds = appDeployerStreamDefinitions.stream().flatMap(sd -> sd.getAppDefinitions().stream()).flatMap(sad -> {
			String key = DeploymentKey.forStreamAppDefinition(sad);
			String id = this.deploymentIdRepository.findOne(key);
			return id != null ? Stream.of(id) : Stream.empty();
		}).sorted((o1, o2) -> o1.compareTo(o2)).collect(toList());

		// Running this this inside the FJP will make sure it is used by the parallel stream
		// Skip first items depending on page size, then take page and discard rest.
		// todo: Use correct pageable values based on the number of apps deployed via all supported StreamDeployers
		return this.forkJoinPool
				.submit(() -> deploymentIds.stream().skip(pageable.getPageNumber() * pageable.getPageSize())
						.limit(pageable.getPageSize()).parallel().map(appDeployer::status).collect(toList()))
				.get();
	}

	private List<List<AppStatus>> getSkipperStatuses(Pageable pageable, List<String> skipperStreamNames)
			throws ExecutionException, InterruptedException{
		//todo: Pageable values correspond to the number apps while Skipper status is done at the stream level.
		//todo: Fix the mismatch with Skipper stream apps' pagination
		return this.forkJoinPool
				.submit(() -> skipperStreamNames.stream().skip(pageable.getPageNumber() * pageable.getPageSize())
						.limit(pageable.getPageSize()).parallel().map(this::skipperStatus).collect(toList())).get();
	}

	private List<AppStatus> skipperStatus(String streamName) {
		List<AppStatus> appStatuses = new ArrayList<>();
		try {
			Info info = this.skipperClient.status(streamName);
			appStatuses.addAll(SkipperStreamDeployer.deserializeAppStatus(info.getStatus().getPlatformStatus()));
		}
		catch (Exception e) {
			// ignore as we query status for all the streams.
		}
		return appStatuses;
	}

	private void enrichWithMetrics(List<AppStatus> statuses) {
		List<ApplicationsMetrics> metricsIn = metricStore.getMetrics();
		Map<String, ApplicationsMetrics.Instance> metricsInstanceMap = new HashMap<>();
		for (ApplicationsMetrics am : metricsIn) {
			for (ApplicationsMetrics.Application a : am.getApplications()) {
				for (ApplicationsMetrics.Instance i : a.getInstances()) {
					metricsInstanceMap.put(i.getGuid(), i);
				}
			}
		}

		for (AppStatus appStatus : statuses) {
			Map<String, AppInstanceStatus> appInstanceStatusMap = appStatus.getInstances();
			appInstanceStatusMap.forEach((k, appInstanceStatus) -> {
				Map<String, String> attributes = appInstanceStatus.getAttributes();
				if (attributes != null && !attributes.isEmpty()) {
					String trackingKey = attributes.get("guid");
					if (metricsInstanceMap.containsKey(trackingKey)) {
						ApplicationsMetrics.Instance metricsAppInstance = metricsInstanceMap.get(trackingKey);
						List<ApplicationsMetrics.Metric> metrics = metricsAppInstance.getMetrics();
						if (metrics != null) {
							for (ApplicationsMetrics.Metric m : metrics) {
								if (ObjectUtils.nullSafeEquals("integration.channel.input.send.mean", m.getName())) {
									appInstanceStatus.getAttributes()
											.put("metrics.integration.channel.input.receiveRate",
													String.format(Locale.US, "%.2f", m.getValue()));
								}
								else if (ObjectUtils
										.nullSafeEquals("integration.channel.output.send.mean", m.getName())) {
									appInstanceStatus.getAttributes().put("metrics.integration.channel.output.sendRate",
											String.format(Locale.US, "%.2f", m.getValue()));
								}
							}
						}
					}
				}
			});
		}
	}

	@RequestMapping("/{id}")
	public AppStatusResource display(@PathVariable String id) {
		AppStatus status = appDeployer.status(id);
		if (status.getState().equals(DeploymentState.unknown)) {
			throw new NoSuchAppException(id);
		}
		return statusAssembler.toResource(status);
	}

	private static class Assembler extends ResourceAssemblerSupport<AppStatus, AppStatusResource> {

		public Assembler() {
			super(RuntimeAppsController.class, AppStatusResource.class);
		}

		@Override
		public AppStatusResource toResource(AppStatus entity) {
			return createResourceWithId(entity.getDeploymentId(), entity);
		}

		@Override
		protected AppStatusResource instantiateResource(AppStatus entity) {
			AppStatusResource resource = new AppStatusResource(entity.getDeploymentId(),
					ControllerUtils.mapState(entity.getState()).getKey());
			List<AppInstanceStatusResource> instanceStatusResources = new ArrayList<>();
			InstanceAssembler instanceAssembler = new InstanceAssembler(entity);
			List<AppInstanceStatus> instanceStatuses = new ArrayList<>(entity.getInstances().values());
			Collections.sort(instanceStatuses, INSTANCE_SORTER);
			for (AppInstanceStatus appInstanceStatus : instanceStatuses) {
				instanceStatusResources.add(instanceAssembler.toResource(appInstanceStatus));
			}
			resource.setInstances(new Resources<>(instanceStatusResources));
			return resource;
		}
	}

	@RestController
	@RequestMapping("/runtime/apps/{appId}/instances")
	@ExposesResourceFor(AppInstanceStatusResource.class)
	public static class AppInstanceController {

		private final AppDeployer appDeployer;

		public AppInstanceController(AppDeployer appDeployer) {
			this.appDeployer = appDeployer;
		}

		@RequestMapping
		public PagedResources<AppInstanceStatusResource> list(Pageable pageable, @PathVariable String appId,
				PagedResourcesAssembler<AppInstanceStatus> assembler) {
			AppStatus status = appDeployer.status(appId);
			if (status.getState().equals(DeploymentState.unknown)) {
				throw new NoSuchAppException(appId);
			}
			List<AppInstanceStatus> appInstanceStatuses = new ArrayList<>(status.getInstances().values());
			Collections.sort(appInstanceStatuses, INSTANCE_SORTER);
			return assembler.toResource(new PageImpl<>(appInstanceStatuses, pageable,
					appInstanceStatuses.size()), new InstanceAssembler(status));
		}

		@RequestMapping("/{instanceId}")
		public AppInstanceStatusResource display(@PathVariable String appId, @PathVariable String instanceId) {
			AppStatus status = appDeployer.status(appId);
			if (status.getState().equals(DeploymentState.unknown)) {
				throw new NoSuchAppException(appId);
			}
			AppInstanceStatus appInstanceStatus = status.getInstances().get(instanceId);
			if (appInstanceStatus == null) {
				throw new NoSuchAppInstanceException(instanceId);
			}
			return new InstanceAssembler(status).toResource(appInstanceStatus);
		}
	}

	private static class InstanceAssembler
			extends ResourceAssemblerSupport<AppInstanceStatus, AppInstanceStatusResource> {

		private final AppStatus owningApp;

		InstanceAssembler(AppStatus owningApp) {
			super(AppInstanceController.class, AppInstanceStatusResource.class);
			this.owningApp = owningApp;
		}

		@Override
		public AppInstanceStatusResource toResource(AppInstanceStatus entity) {
			return createResourceWithId("/" + entity.getId(), entity, owningApp.getDeploymentId());
		}

		@Override
		protected AppInstanceStatusResource instantiateResource(AppInstanceStatus entity) {
			return new AppInstanceStatusResource(entity.getId(), ControllerUtils.mapState(entity.getState()).getKey(),
					entity.getAttributes());
		}
	}
}
