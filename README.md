jenkins-kubernetes-plugin
=========================

Jenkins plugin to run dynamic slaves in a Kubernetes/Docker environment.

Based on the [Scaling Docker with Kubernetes](http://www.infoq.com/articles/scaling-docker-with-kubernetes) article,
automates the scaling of Jenkins slaves running in Kubernetes.

The plugin creates a Kubernetes Pod for each slave started,
defined by the Docker image to run, and stops it after each build.

Slaves are launched using JNLP, so it is expected that the image connects automatically to the Jenkins master.
For that some environment variables are automatically injected:

* `JENKINS_URL`: Jenkins web interface url
* `JENKINS_JNLP_URL`: url for the jnlp definition of the specific slave
* `JENKINS_SECRET`: the secret key for authentication

This plugin base on [jenkinsci/kubernetes-plugin](https://github.com/jenkinsci/kubernetes-plugin) and will only work with pod template file at the moment

Use a slave json template making sure the name of the image has slave in it

````json

{
  "apiVersion": "v1",
  "kind": "Pod",
  "metadata": {
    "name": "slave"
  },
  "spec": {
    "volumes": [
      {
        "name": "jenkins-home",
        "persistentVolumeClaim": {
          "claimName": "nfs"
        }
      },
      {
        "name": "slave-workspace",
        "persistentVolumeClaim": {
          "claimName": "slave-workspace-nfs-pv"
        }
      }
    ],
    "containers": [
      {
        "name": "jenkins-slave",
        "image": "12345678910.dkr.ecr.us-east-1.amazonaws.com/glide/jenkins-slave:1.0.0",
        "securityContext": { "privileged": "true" },
        "volumeMounts": [
          {
            "mountPath": "/jenkins-docker",
            "name": "jenkins-docker"
          },
          {
            "mountPath": "/var/jenkins_home",
            "name": "jenkins-home"
          }
        ],
        "ports": [
          {
            "containerPort": 8080
          }
        ]
      }
    ]
  }
}

````

