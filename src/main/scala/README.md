#Dataproc init auth

##File server application which has a custom authentication to allow administrators to differentiate between cluster members when the secret is stored on the server. It provides a unique way of distributing secrets to the dataproc cluster members via the initialization action.

#Setup 

Create a Dataproc Cluster which acts as a server and run a startup script to start the server
```
ssh -v authtest 
./start.sh
```
Create a Dataproc Cluster which acts as a client. 
```
gcloud beta dataproc clusters create c$(date +%s)
--region us-east1 --subnet projects/prod-abc-host/regions/us-east1/subnetworks/default 
--no-address --zone us-east1-b --single-node --master-machine-type n1-standard-4 
--master-boot-disk-size 100 --image-version 1.4-debian9 
--max-idle 3600s --tags nat --project dp-init 
--service-account svc
```
Note down the IP address of the cluster and modify the config file with the IP address 

```
vim ~/.ssh/config
```

Copy the client script to the cluster and run the client 
```
scp -p client.sh dp1:
ssh dp1:
./client.sh
```

