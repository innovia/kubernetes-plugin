package org.csanchez.jenkins.plugins.kubernetes;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;

import javax.annotation.CheckForNull;

import org.apache.commons.lang.StringUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import com.fasterxml.jackson.core.JsonParser;
import com.google.common.base.CaseFormat;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Environment;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.fabric8.kubernetes.api.Kubernetes;
import io.fabric8.kubernetes.api.KubernetesHelper;
import io.fabric8.kubernetes.api.model.AWSElasticBlockStoreVolumeSource;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.EmptyDirVolumeSource;
import io.fabric8.kubernetes.api.model.AWSElasticBlockStoreVolumeSource;
import io.fabric8.kubernetes.api.model.PersistentVolume;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimVolumeSource;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HostPathVolumeSource;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.SecurityContext;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.EnvVar;



import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.utils.Filter;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import jenkins.tasks.SimpleBuildWrapper.Context;

import org.json.simple.parser.JSONParser;
/**
 * Kubernetes cloud provider.
 *
 * Starts slaves in a Kubernetes cluster using defined Docker templates for each label.
 *
 * @author Carlos Sanchez carlos@apache.org
 */
public class KubernetesCloud extends Cloud {
	private static final Logger LOGGER = Logger.getLogger(KubernetesCloud.class.getName());
	private static final Pattern SPLIT_IN_SPACES = Pattern.compile("([^\"]\\S*|\".+?\")\\s*");

	private static final String DEFAULT_ID = "jenkins-slave-default";

	/** label for all pods started by the plugin */
	private static final Map<String, String> POD_LABEL = ImmutableMap.of("jenkins", "slave");

	private static final String CONTAINER_NAME = "slave";

	private final List<PodTemplate> templates;
	private final String serverUrl;
	@CheckForNull
	private String serverCertificate;

	private boolean skipTlsVerify;

	private String namespace;
	private final String jenkinsUrl;
	@CheckForNull
	private String jenkinsTunnel;
	@CheckForNull
	private String credentialsId;
	private final int containerCap;

	private transient Kubernetes connection;

	@DataBoundConstructor
	public KubernetesCloud(String name, List<? extends PodTemplate> templates, String serverUrl, String namespace,
			String jenkinsUrl, String containerCapStr, int connectTimeout, int readTimeout) {
		super(name);

		Preconditions.checkArgument(!StringUtils.isBlank(serverUrl));

		this.serverUrl = serverUrl;
		this.namespace = namespace;
		this.jenkinsUrl = jenkinsUrl;
		if (templates != null)
			this.templates = new ArrayList<PodTemplate>(templates);
		else
			this.templates = new ArrayList<PodTemplate>();

		if (containerCapStr.equals("")) {
			this.containerCap = Integer.MAX_VALUE;
		} else {
			this.containerCap = Integer.parseInt(containerCapStr);
		}
	}


	public List<PodTemplate> getTemplates() {
		return templates;
	}

	public String getServerUrl() {
		return serverUrl;
	}

	public String getServerCertificate() {
		return serverCertificate;
	}

	@DataBoundSetter
	public void setServerCertificate(String serverCertificate) {
		this.serverCertificate = Util.fixEmpty(serverCertificate);
	}

	public boolean isSkipTlsVerify() {
		return skipTlsVerify;
	}

	@DataBoundSetter
	public void setSkipTlsVerify(boolean skipTlsVerify) {
		this.skipTlsVerify = skipTlsVerify;
	}

	public String getNamespace() {
		return namespace;
	}

	public String getJenkinsUrl() {
		return jenkinsUrl;
	}

	public String getJenkinsTunnel() {
		return jenkinsTunnel;
	}

	@DataBoundSetter
	public void setJenkinsTunnel(String jenkinsTunnel) {
		this.jenkinsTunnel = Util.fixEmpty(jenkinsTunnel);
	}

	
	public String getCredentialsId() {
		return credentialsId;
	}

	@DataBoundSetter
	public void setCredentialsId(String credentialsId) {
		this.credentialsId = Util.fixEmpty(credentialsId);
	}

	public String getContainerCapStr() {
		if (containerCap == Integer.MAX_VALUE) {
			return "";
		} else {
			return String.valueOf(containerCap);
		}
		}

	/**
	 * Connects to Docker.
	 *
	 * @return Docker client.
	 */
	public Kubernetes connect() throws UnrecoverableKeyException, NoSuchAlgorithmException, KeyStoreException {

		LOGGER.log(Level.FINE, "Building connection to Kubernetes host " + name + " URL " + serverUrl);

		if (connection == null) {
			synchronized (this) {
				if (connection != null)
					return connection;

				connection = new KubernetesFactoryAdapter(serverUrl, serverCertificate, credentialsId, skipTlsVerify)
						.createKubernetes();
			}
		}
		return connection;

	}

	private String getIdForLabel(Label label) {
		if (label == null) {
			return DEFAULT_ID;
		}
		return "jenkins-" + label.getName();
	}

	
	
	
	private Pod getPodTemplate(KubernetesSlave slave, Label label) {
		try {	
			final PodTemplate template = getTemplate(label);
			String id = getIdForLabel(label);

			String podTemplateFilePath = template.getTemplateFilePath();
			
			// read pod.json
			JSONParser parser = new JSONParser();

			JSONObject podJsonTemplateObject;
			
			org.json.simple.JSONObject obj = (org.json.simple.JSONObject) parser.parse(new FileReader(podTemplateFilePath));
			podJsonTemplateObject = new JSONObject(obj.toJSONString());
			
			JSONObject podTemplateSpecs  = podJsonTemplateObject.getJSONObject("spec");

			
			// initialize Pod Components
			Pod pod = new Pod();
			PodSpec podSpec = new PodSpec(); // pod spec place holder for volumes and containers arrays
			
			// set the name of the pod to the slave name
			KubernetesHelper.setName(pod, slave.getNodeName());

			pod.getMetadata().setLabels(getLabelsFor(id));

		    // SPEC -> nodeSelector
			if (podTemplateSpecs.has("nodeSelector")) {
				JSONObject nodeSelector = podTemplateSpecs.getJSONObject("nodeSelector");

				Map nodeSelectorMap = new HashMap<String,String> ();
				String nodeSelectorKey = (String) nodeSelector.keySet().toArray()[0];
				nodeSelectorMap.put(nodeSelectorKey, nodeSelector.get(nodeSelectorKey));
				
				podSpec.setNodeSelector(nodeSelectorMap);
				  
			}
			
			// SPEC -> VOLUMES
			// go over all the volumes under SPEC, add manifestVolume to volumes object list
			List<Volume> volumes = new ArrayList<Volume>(); // prep new volumes Array
			if (podTemplateSpecs.has("volumes")) {
				JSONArray templateVolumes = podTemplateSpecs.getJSONArray("volumes");
				System.out.println("adding volumes " + templateVolumes + "from pod template to pod specs");

				int templateVolumesSize = templateVolumes.length(); 
				
				for (int i=0; i < templateVolumesSize ; i++ ) {
					JSONObject templateVolume = templateVolumes.getJSONObject(i); // get the current templateVolume
					
					Volume manifestVolume = new Volume();
					
					manifestVolume.setName(templateVolume.getString("name"));
					
					if (templateVolume.has("hostPath")) {
						JSONObject hostPath = templateVolume.getJSONObject("hostPath") ;
						if (hostPath != null) {
							
							manifestVolume.setHostPath(new HostPathVolumeSource(hostPath.getString("path")));
						}
					}
				
					if (templateVolume.has("emptyDir")) {
						manifestVolume.setEmptyDir(new EmptyDirVolumeSource()); 
					}
				
					if (templateVolume.has("persistentVolumeClaim")) {
						PersistentVolumeClaimVolumeSource pvc = new PersistentVolumeClaimVolumeSource(templateVolume.getJSONObject("persistentVolumeClaim").getString("claimName"), false);
						manifestVolume.setPersistentVolumeClaim(pvc);
					}
					
					volumes.add(manifestVolume);
				}
								
					podSpec.setVolumes(volumes);
			} else {
				 System.out.println("did not find volumes on the pod template given");
			}
		   
			// SPEC -> CONTAINERS
			System.out.println("building containers from templete");

			List<Container> containers = new ArrayList<Container>(); // new containers Array
			JSONArray templateContainers = podTemplateSpecs.getJSONArray("containers");

			// get containers from the JSON file
			int templateContainersSize = templateContainers.length();
			
			for (int i=0; i < templateContainersSize ; i++ ) {
				JSONObject templateContainer = templateContainers.getJSONObject(i); //current container JSON Object
				System.out.println("Current Container: " + templateContainer);
				Container manifestContainer = new Container();
				String containerName =  templateContainer.getString("name");
				
				if (template.isPrivileged()) {
					manifestContainer.setSecurityContext(new SecurityContext(null, true, null, null));
				}
				
				boolean isSlave = containerName.contains("slave");

				if (isSlave) {
					// command: SECRET SLAVE_NAME
					System.out.println("adding args and commands for slave container: " + containerName);
					List<String> cmd = parseDockerCommand(template.getCommand());
					List<String> args = parseDockerCommand(template.getArgs());
					args = args == null ? new ArrayList<String>(2) : args;
					args.add(slave.getComputer().getJnlpMac()); // secret
					args.add(slave.getComputer().getName()); // name
					System.out.println("command to add to pod: " + cmd);
					manifestContainer.setCommand(cmd);
					System.out.println("args to add to pod: " + args);
					manifestContainer.setArgs(args);				
				}
				
				manifestContainer.setName(containerName);
				manifestContainer.setImage(templateContainer.getString("image"));

				// CONTAINER -> WorkingDir
				if (templateContainer.has("workingDir")) {
					System.out.println("Adding Working Dir");
					manifestContainer.setWorkingDir(templateContainer.getString("workingDir"));
				}
				
				// CONTAINER -> VolumesMounts
				List<VolumeMount> volumeMounts = new ArrayList<VolumeMount>(); // new container volume mounts Array

				if (templateContainer.has("volumeMounts")) {
					JSONArray templateContainerVolumeMounts = templateContainer.getJSONArray("volumeMounts");
					System.out.println("adding volumeMounts" + templateContainerVolumeMounts + " for container: " + containerName);

					int templateContainerVolumeMountsSize = templateContainerVolumeMounts.length(); 
					for (int j=0; j < templateContainerVolumeMountsSize; j++ ) {
						JSONObject templateContainerVolumeMount = templateContainerVolumeMounts.getJSONObject(j); //get the current volume mount for the current contianer
						volumeMounts.add(new VolumeMount(templateContainerVolumeMount.getString("mountPath"), templateContainerVolumeMount.getString("name"), false));
					}
				} else {
					System.out.println("no volume mounts found for container:" + containerName);
				}
				
				manifestContainer.setVolumeMounts(volumeMounts); // add volumeMounts to container
			
				
				// CONTAINER -> Ports
				if (templateContainer.has("ports")) {
					System.out.println("adding ports" + templateContainer.getJSONArray("ports").toString()  + " for container: " + containerName);
				
					JSONArray templateContainerPorts = templateContainer.getJSONArray("ports");
					List<ContainerPort> ports = null;
					ports = new ArrayList<ContainerPort>();
					int templeContainerPortsSize = templateContainerPorts.length();
					
					for (int p=0; p < templeContainerPortsSize; p++ ) {	
						ContainerPort port;
						try {
							JSONObject templateContainerPort = templateContainerPorts.getJSONObject(p); //get the current port from the ports for the current container
							port = new ContainerPort(templateContainerPort.getInt("containerPort"), null, null, null, "TCP");
							
						} catch (JSONException e) {
							continue;
						}
						ports.add(port);
					}
					manifestContainer.setPorts(ports);
				} else {
					System.out.println("no ports found for container: " + containerName);
				}
				 
				// CONTAINER -> Command
				if(templateContainer.has("command") && isSlave == false){
					JSONArray templateContainerCommand = templateContainer.getJSONArray("command");
					System.out.println("adding command " + templateContainerCommand + " for container: " + containerName);

					int templateContainerCommandSize = templateContainerCommand.length();
					
					List<String> cmd = new ArrayList<String>(templateContainerCommandSize);
					for (int j = 0; j < templateContainerCommandSize; j++) {						
						cmd.add(templateContainerCommand.getString(j));
					}
					manifestContainer.setCommand(cmd);
				}
				
				// CONTAINER -> Args
				if(templateContainer.has("args") && isSlave == false){
					JSONArray templateContainerArgs = templateContainer.getJSONArray("args");
					System.out.println("adding args " + templateContainerArgs + "for container: " + containerName);

					int templateContainerArgsSize = templateContainerArgs.length();
					
					List<String> args = new ArrayList<String>(templateContainerArgsSize);
					for (int j = 0; j < templateContainerArgsSize; j++) {						
						args.add(templateContainerArgs.getString(j));
					}
					manifestContainer.setArgs(args);
				}
				
				// CONTAINER -> Env Vars			
				// if container has env vars
				List<EnvVar> enviroments = new ArrayList<EnvVar>();
				
				if (templateContainer.has("env")) {
					System.out.println("adding env vars for container: " + containerName);
					JSONArray templateContainerEnvVars = templateContainer.getJSONArray("env");

					int templateContainerEnvVarsSize = templateContainerEnvVars.length();
					for (int v=0; v < templateContainerEnvVarsSize; v++ ) {
						try {
							JSONObject templateContainerEnvVar = templateContainerEnvVars.getJSONObject(v); //get the current env var object
							enviroments.add(new EnvVar(templateContainerEnvVar.getString("name"), templateContainerEnvVar.getString("value"), null));
						} catch (JSONException e) {
							continue;
						}
					}
				
					// always add some env vars
					enviroments.add(new EnvVar("JENKINS_SECRET", slave.getComputer().getJnlpMac(), null));
					enviroments.add(new EnvVar("JENKINS_LOCATION_URL", JenkinsLocationConfiguration.get().getUrl(), null));

					String url = StringUtils.isBlank(jenkinsUrl) ? JenkinsLocationConfiguration.get().getUrl() : jenkinsUrl;
					enviroments.add(new EnvVar("JENKINS_URL", url, null));
					if (!StringUtils.isBlank(jenkinsTunnel)) {
						enviroments.add(new EnvVar("JENKINS_TUNNEL", jenkinsTunnel, null));
					}
					url = url.endsWith("/") ? url : url + "/";
					enviroments.add(new EnvVar("JENKINS_JNLP_URL", url + slave.getComputer().getUrl() + "slave-agent.jnlp", null));
					
					manifestContainer.setEnv(enviroments);
				} else {
					System.out.println("no env vars found for container: " + containerName);
				}		
				containers.add(manifestContainer);
			}
			
			pod.setSpec(podSpec);
			podSpec.setContainers(containers);
			//podSpec.setRestartPolicy("Never");
			return pod;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}


	private Map<String, String> getLabelsFor(String id) {
		return ImmutableMap.<String, String> builder().putAll(POD_LABEL).putAll(ImmutableMap.of("name", id)).build();
	}

	/**
	 * Split a command in the parts that Docker need
	 *
	 * @param dockerCommand
	 * @return
	 */
	List<String> parseDockerCommand(String dockerCommand) {
		if (dockerCommand == null || dockerCommand.isEmpty()) {
			return null;
		}
		// handle quoted arguments
		Matcher m = SPLIT_IN_SPACES.matcher(dockerCommand);
		List<String> commands = new ArrayList<String>();
		while (m.find()) {
			commands.add(m.group(1).replace("\"", ""));
		}
		return commands;
	}

	@Override
	public synchronized Collection<NodeProvisioner.PlannedNode> provision(final Label label, final int excessWorkload) {
		try {

			LOGGER.log(Level.INFO, "Excess workload after pending Spot instances: " + excessWorkload);
			
			List<NodeProvisioner.PlannedNode> r = new ArrayList<NodeProvisioner.PlannedNode>();

			final PodTemplate t = getTemplate(label);

			for (int i = 1; i <= excessWorkload; i++) {
				if (!addProvisionedSlave(t, label)) {
					break;
				}

				r.add(new NodeProvisioner.PlannedNode(t.getDisplayName(), Computer.threadPoolForRemoting
						.submit(new ProvisioningCallback(this, t, label)), 1));
			}
			return r;
		} catch (Exception e) {
			LOGGER.log(Level.WARNING, "Failed to count the # of live instances on Kubernetes", e);
			return Collections.emptyList();
		}
	}

	private class ProvisioningCallback implements Callable<Node> {
		private final KubernetesCloud cloud;
		private final PodTemplate t;
		private final Label label;


		public ProvisioningCallback(KubernetesCloud cloud, PodTemplate t, Label label) {
			this.cloud = cloud;
			this.t = t;
			this.label = label;
		}

		public Node call() throws Exception {
			KubernetesSlave slave = null;
			try {

				slave = new KubernetesSlave(t, getIdForLabel(label), cloud, label);
				Jenkins.getInstance().addNode(slave);
						
				Pod pod = getPodTemplate(slave, label);
				// Why the hell doesn't createPod return a Pod object ?
				String podJson = connect().createPod(pod, namespace);

				String podId = pod.getMetadata().getName();
				LOGGER.log(Level.INFO, "Created Pod: {0}", podId);

				// We need the pod to be running and connected before returning
				// otherwise this method keeps being called multiple times
				ImmutableList<String> validStates = ImmutableList.of("Running");

				int i = 0;
				int j = 100; // wait 600 seconds

				// wait for Pod to be running
				for (; i < j; i++) {
					LOGGER.log(Level.INFO, "Waiting for Pod to be scheduled ({1}/{2}): {0}", new Object[] {podId, i, j});
					Thread.sleep(6000);
					pod = connect().getPod(podId, namespace);
					if (pod == null) {
						throw new IllegalStateException("Pod no longer exists: " + podId);
					}
					ContainerStatus info = getContainerStatus(pod, CONTAINER_NAME);
					if (info != null) {
						if (info.getState().getWaiting() != null) {
							// Pod is waiting for some reason
							LOGGER.log(Level.INFO, "Pod is waiting {0}: {1}",
									new Object[] { podId, info.getState().getWaiting() });
							// break;
						}
						if (info.getState().getTerminated() != null) {
							throw new IllegalStateException("Pod is terminated. Exit code: "
									+ info.getState().getTerminated().getExitCode());
						}
					}
					if (validStates.contains(pod.getStatus().getPhase())) {
						break;
					}
				}
				String status = pod.getStatus().getPhase();
				if (!validStates.contains(status)) {
					throw new IllegalStateException("Container is not running after " + j + " attempts: " + status);
				}

				// now wait for slave to be online
				for (; i < j; i++) {
					if (slave.getComputer() == null) {
						throw new IllegalStateException("Node was deleted, computer is null");
					}
					if (slave.getComputer().isOnline()) {
						break;
					}
					LOGGER.log(Level.INFO, "Waiting for slave to connect ({1}/{2}): {0}", new Object[] { podId,
							i, j });
					Thread.sleep(1000);
				}
				if (!slave.getComputer().isOnline()) {
					throw new IllegalStateException("Slave is not connected after " + j + " attempts: " + status);
				}

				return slave;
			} catch (Throwable ex) {
				LOGGER.log(Level.SEVERE, "Error in provisioning; slave={0}, template={1}", new Object[] { slave, t });
				ex.printStackTrace();
				throw Throwables.propagate(ex);
			}
		}
	}

	private ContainerStatus getContainerStatus(Pod pod, String containerName) {

		for (ContainerStatus status : pod.getStatus().getContainerStatuses()) {
			if (status.getName().equals(containerName)) return status;
		}
		return null;
	}

	/**
	 * Check not too many already running.
	 *
	 */
	private boolean addProvisionedSlave(PodTemplate template, Label label) throws Exception {
		if (containerCap == 0) {
			return true;
		}

		final Filter<Pod> slaveFilter = KubernetesHelper.createPodFilter(POD_LABEL);
		final Filter<Pod> nameFilter = KubernetesHelper.createPodFilter(ImmutableMap.of("name", getIdForLabel(label)));

		// fabric8 does not support labelSelector query parameter
		PodList allPods = connect().getPods(namespace);
		int c = 0;
		int t = 0;
		for (Pod pod : allPods.getItems()) {
			if (slaveFilter.matches(pod)) {
				if (++c > containerCap) {
					LOGGER.log(Level.INFO, "Total container cap of " + containerCap + " reached, not provisioning.");
					return false; // maxed out
				}
			}
			if (nameFilter.matches(pod)) {
				if (++t > template.getInstanceCap()) {
					LOGGER.log(Level.INFO, "Template instance cap of " + template.getInstanceCap() + " reached for template "
							+ template.getImage() + ", not provisioning.");
					return false; // maxed out
				}
			}
		}

		return true;
	}

	@Override
	public boolean canProvision(Label label) {
		return getTemplate(label) != null;
	}

	public PodTemplate getTemplate(String template) {
		for (PodTemplate t : templates) {
			if (t.getImage().equals(template)) {
				return t;
			}
		}
		return null;
	}

	/**
	 * Gets {@link PodTemplate} that has the matching {@link Label}.
	 * @param label label to look for in templates
	 * @return the template
	 */
	public PodTemplate getTemplate(Label label) {
		for (PodTemplate t : templates) {
			if (label == null || label.matches(t.getLabelSet())) {
				return t;
			}
		}
		return null;
	}

	/**
	 * Add a new template to the cloud
	 * @param t docker template
	 */
	public void addTemplate(PodTemplate t) {
		this.templates.add(t);
		// t.parent = this;
	}

	/**
	 * Remove a
	 *
	 * @param t docker template
	 */
	public void removeTemplate(PodTemplate t) {
		this.templates.remove(t);
	}

	@Extension
	public static class DescriptorImpl extends Descriptor<Cloud> {
				
		@Override
		public String getDisplayName() {
			return "Kubernetes";
		}

		public FormValidation doTestConnection(@QueryParameter URL serverUrl, @QueryParameter String credentialsId,
				@QueryParameter String serverCertificate,
				@QueryParameter boolean skipTlsVerify,
				@QueryParameter String namespace) throws Exception {

			Kubernetes kube = new KubernetesFactoryAdapter(serverUrl.toExternalForm(),
					Util.fixEmpty(serverCertificate), Util.fixEmpty(credentialsId), skipTlsVerify).createKubernetes();
			kube.getPods(namespace);

			return FormValidation.ok("Connection successful");
		}

		public ListBoxModel doFillCredentialsIdItems(@QueryParameter URL serverUrl) {
			return new StandardListBoxModel()
					.withEmptySelection()
					.withMatching(
							CredentialsMatchers.anyOf(
									CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class),
									CredentialsMatchers.instanceOf(BearerTokenCredential.class)
									),
							CredentialsProvider.lookupCredentials(StandardCredentials.class,
									Jenkins.getInstance(),
									ACL.SYSTEM,
									URIRequirementBuilder.fromUri(serverUrl.toExternalForm()).build()));

		}

	}

	@Override
	public String toString() {
		return String.format("KubernetesCloud name: %n serverUrl: %n", name, serverUrl);
	}

	private Object readResolve() {
		if (namespace == null) namespace = "jenkins-slave";
		return this;
	}

}
