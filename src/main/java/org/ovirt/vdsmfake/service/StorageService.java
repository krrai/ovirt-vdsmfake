/**
 Copyright (c) 2016 Red Hat, Inc.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

           http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/
package org.ovirt.vdsmfake.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.ovirt.vdsmfake.domain.DataCenter;
import org.ovirt.vdsmfake.domain.Host;
import org.ovirt.vdsmfake.domain.StorageDomain;
import org.ovirt.vdsmfake.domain.Task;
import org.ovirt.vdsmfake.domain.VdsmManager;
import org.ovirt.vdsmfake.domain.Volume;
import org.ovirt.vdsmfake.task.TaskProcessor;
import org.ovirt.vdsmfake.task.TaskRequest;
import org.ovirt.vdsmfake.task.TaskType;

@Singleton
public class StorageService extends AbstractService {

    private static StorageService instance = new StorageService();

    @Inject
    private VdsmManager vdsmManager;

    public static StorageService getInstance() {
        return instance;
    }

    public StorageService() {
    }

    /**
     * Connect data center to the host.
     */
    public Map connectStoragePool(
            String spUUID, Integer hostID, String scsiKey, String msdUUID, Integer masterVersion) {

        // save to model
        final Host host = getActiveHost();
        host.setSpUUID(spUUID);
        updateHost(host);

        host.getDataCenter().setMasterStorageDomainId(msdUUID);
        host.getDataCenter().setMasterVersion(masterVersion);

        // store to database
        setMasterDomain(spUUID, msdUUID);

        log.info("Data center {} connected.", spUUID);

        // send ok
        return getOKStatus();
    }

    private void setMasterDomain(String spUuid, String masterSdUuid) {

        log.info("Setting master domain, sp: {}, master sd: {}: ", spUuid, masterSdUuid);

        if (masterSdUuid == null) {
            return;
        }

        for (StorageDomain storageDomain : getActiveHost().getStorageDomains().values()) {
            if (masterSdUuid.equals(storageDomain.getId())) {
                storageDomain.setDomainRole(StorageDomain.DomainRole.MASTER);
            } else {
                storageDomain.setDomainRole(StorageDomain.DomainRole.REGULAR);
            }
        }

    }

    public Map connectStorageServer(Integer domType, String spUUID, List<Map> storageDomains) {
        try {
            final Host host = getActiveHost();
            // save to model
            updateHost(host);

            Map resultMap = getOKStatus();

            List statusList = new ArrayList();
            resultMap.put("statuslist", statusList);

            // extract
            for(int i=0; storageDomains != null && i < storageDomains.size();i++) {
                final Map storageDomainMap = storageDomains.get(i);

                final String id = (String) storageDomainMap.get("id");
                final String connection = (String) storageDomainMap.get("connection");

                log.info("Adding storage connection, spUUID: {} id: {}, connection: {}",
                        new Object[] { spUUID, id, connection });

                host.getStorageConnections().put(id, connection);

                // response
                final Map storageStatusMap = map();
                storageStatusMap.put("status", Integer.valueOf(0));
                storageStatusMap.put("id", id);
                statusList.add(storageStatusMap);
            }

            log.info("Storage server {} connected.", spUUID);

            return resultMap;
        } catch (Exception e) {
            throw error(e);
        }
    }

    public Map validateStorageServerConnection(Integer domType, String spUUID, List<Map> storageDomains) {
        try {
            final Map resultMap = getOKStatus();
            final List statusList = new ArrayList();
            resultMap.put("statusList", statusList);

            // extract
            for(int i=0;i < storageDomains.size();i++) {
                final Map storageDomainMap = storageDomains.get(i);

                final String id = (String) storageDomainMap.get("id");
                final String connection = (String) storageDomainMap.get("connection");

                boolean valid = getActiveHost().getStorageDomains().get(id).getConnection().equals(connection);
                if (!valid) {
                    return getStatusMap("error", 1);
                }
                Map storageStatusMap = map();
                storageStatusMap.put("status", Integer.valueOf(0));
                storageStatusMap.put("id", id);
                statusList.add(storageStatusMap);
            }
            log.info("Storage server {} validated.", spUUID);
            return resultMap;
        } catch (Exception e) {
            throw error(e);
        }
    }

    public Map createStoragePool(int poolType, String spUUID, String poolName, String masterDom, List domList,
            int masterVersion, String lockPolicy, int lockRenewalIntervalSec, int leaseTimeSec, int ioOpTimeoutSec,
            int leaseRetries) {
        try {

            Host activeHost = getActiveHost();
            final DataCenter dataCenter = activeHost.getDataCenter();
            dataCenter.setId(spUUID);
            dataCenter.setName(poolName);
            dataCenter.setMasterStorageDomainId(masterDom);
            dataCenter.setMasterVersion(masterVersion);

            // store to database
            setMasterDomain(spUUID, masterDom);

            log.info("Storage pool {} created, master domain: {}, total domains: {}",
                    new Object[] { spUUID, dataCenter.getMasterStorageDomainId(),
                            activeHost.getStorageDomains().size() });

            // send ok
            return getOKStatus();
        } catch (Exception e) {
            throw error(e);
        }
    }

    public Map createStorageDomain(
            Integer storageType,
            String sdId,
            String domainName,
            String typeSpecificArg,
            Integer domClass,
            String storageFormatType) {
        log.info("Storage domain sdUUID: {}, name: {} created.", sdId, domainName);

        getActiveHost().getStorageDomains().compute(
                sdId,
                (id, entity) -> {
                    StorageDomain sd = entity == null ? new StorageDomain() : entity;
                    sd.setId(id.toString());
                    sd.setName(domainName);
                    sd.setConnection(typeSpecificArg);
                    sd.setDomainClass(StorageDomain.DomainClass.getByCode(domClass));
                    sd.setStorageType(StorageDomain.StorageType.getByCode(storageType));
                    return sd;
                });

        // send ok
        return getOKStatus();
    }

    public Map disconnectStorageServer(Integer domType, String spUUID, List<Map> storageDomains) {
        Map resultMap = getOKStatus();

        List statusList = new ArrayList();
        resultMap.put("statuslist", statusList);

        Map<String, String> storageConections = getActiveHost().getStorageConnections();

        // extract
        for(int i=0;i < storageDomains.size();i++) {
            Map storageDomainMap = storageDomains.get(i);

            String id = (String) storageDomainMap.get("id");
            storageConections.remove(id);


            // response
            Map storageStatusMap = map();
            storageStatusMap.put("status", Integer.valueOf(0));
            storageStatusMap.put("id", id);
            statusList.add(storageStatusMap);
        }

        return resultMap;
    }

    public Map getStoragePoolInfo(String spUUID) {
        try {
            final Host host = getActiveHost();
            final DataCenter dataCenter = host.getDataCenter();

            Map resultMap = map();

            Map infoMap = map();
            infoMap.put("spm_id", host.getSpmId());
            infoMap.put("master_uuid", dataCenter.getMasterStorageDomainId()); // 553c2cb4-54d1-4c30-b2c2-6cb41a03518d
            infoMap.put("name", dataCenter.getName());
            infoMap.put("version", "3");

            String isoDomainId = null;
//            StorageDomain masterStorageDomain = null;

            int i=0;
            StringBuilder b = new StringBuilder();
            for (StorageDomain storageDomain : host.getStorageDomains().values()) {

                //force ACTIVE Status for storage domain.
                if (!storageDomain.getDomainStatus().equals(StorageDomain.DomainStatus.ACTIVE)){
                    storageDomain.setDomainStatus(StorageDomain.DomainStatus.ACTIVE);
                }
                // TODO: name of storage domain not sent, API talk with id no naming relations, might be problematic.
                // storageDomain.setName("sd_fake")

                b.append(storageDomain.getId()).append(":").append(storageDomain.getDomainStatus().getName());

                if (i != host.getStorageDomains().values().size() - 1) {
                    b.append(",");
                }

                if (storageDomain.getDomainClass() == StorageDomain.DomainClass.ISO) {
                    isoDomainId = storageDomain.getId();
                }

//                if (dataCenter.getMasterStorageDomainId().equals(storageDomain.getId())) {
//                    masterStorageDomain = storageDomain;
//                }
            }

            infoMap.put("domains", b.toString());

            infoMap.put("pool_status", dataCenter.getPoolStatus()); // connected
            infoMap.put("isoprefix",
                    isoDomainId == null ? "" :
                    "/rhev/data-center/" + spUUID + "/" + isoDomainId + "/images/11111111-1111-1111-1111-111111111111");
            infoMap.put("type", dataCenter.getStorageType().toString()); // NFS
            infoMap.put("master_ver", dataCenter.getMasterVersion());
            infoMap.put("lver", host.getSpmLver()); //  Integer.valueOf(2)

            Map dominfo = map();
            for (StorageDomain storageDomain : host.getStorageDomains().values()) {
                Map dominfoChild = map();
                dominfo.put(storageDomain.getId(), dominfoChild); // 67070f56-027f-4ece-958d-e226639b622b

                dominfoChild.put("status", storageDomain.getDomainStatus().getName());
                dominfoChild.put("diskfree", "59586904064");
                dominfoChild.put("alerts", lst()); // empty list
                dominfoChild.put("disktotal", "274792972288");
            }

            resultMap.put("info", infoMap);
            resultMap.put("dominfo", dominfo);

            return resultMap;
        } catch (Exception e) {
            throw error(e);
        }
    }

    public Map getStorageDomainStats(String sdUUID) {
        Map resultMap = getOKStatus();

        Map statsMap = map();
        statsMap.put("mdasize", Integer.valueOf(0));
        statsMap.put("mdathreshold", Boolean.TRUE);
        statsMap.put("mdavalid", Boolean.TRUE);
        statsMap.put("diskfree", "57503383552");
        statsMap.put("disktotal", "274792972288");
        statsMap.put("mdafree", Integer.valueOf(0));

        resultMap.put("stats", statsMap);

        return resultMap;
    }

    private void activateClearance(StorageDomain storageDomain){
        storageDomain.setDomainStatus(StorageDomain.DomainStatus.ACTIVE);
    }

    public Map activateStorageDomain(String sdUUID, String spUUID) {
        try {
            log.info("Activating storage domain, spUUID: {} sdUUID: {}", new Object[] { spUUID, sdUUID });

            final StorageDomain storageDomain = getActiveHost().getStorageDomains().get(sdUUID);
            if (storageDomain != null) {
                activateClearance(storageDomain);
                log.info("storage were activated {} {}", storageDomain.getName(), sdUUID);
            } else {
                log.warn("No storage domains were activated for storage domain '{}' and storage pool '{}'",
                        sdUUID,
                        spUUID);
            }

            return getOKStatus();
        } catch (Exception e) {
            throw error(e);
        }
    }

    public Map deactivateStorageDomain(String sdUUID, String spUUID, String msdUUID, int masterVersion) {
        try {
            log.info("Deactivating storage domain, spUUID: {} sdUUID: {}", new Object[] { spUUID, sdUUID });

            final StorageDomain storageDomain = getActiveHost().getStorageDomains().get(sdUUID);
            storageDomain.setDomainStatus(StorageDomain.DomainStatus.ATTACHED);

            return getOKStatus();
        } catch (Exception e) {
            throw error(e);
        }
    }

    public Map attachStorageDomain(String sdUUID, String spUUID) {
        try {
            log.info("Attaching storage domain, spUUID: {} sdUUID: {}", new Object[] { spUUID, sdUUID });

            final StorageDomain storageDomain = getActiveHost().getStorageDomains().get(sdUUID);
            storageDomain.setDomainStatus(StorageDomain.DomainStatus.ATTACHED);
            storageDomain.setDataCenter(getActiveHost().getDataCenter());

            return getOKStatus();
        } catch (Exception e) {
            throw error(e);
        }
    }

    public Map detachStorageDomain(String sdUUID, String spUUID, String msdUUID, int masterVersion) {
        try {
            log.info("Detaching storage domain, spUUID: {} sdUUID: {}", new Object[] { spUUID, sdUUID });

            Host activeHost = getActiveHost();
            final StorageDomain storageDomain = activeHost.getStorageDomains().get(sdUUID);
            storageDomain.setDomainStatus(StorageDomain.DomainStatus.UNATTACHED);
            getActiveHost().getStorageDomains().remove(storageDomain.getId());
            storageDomain.setDataCenter(null);

            return getOKStatus();
        } catch (Exception e) {
            throw error(e);
        }
    }

    public Map refreshStoragePool(String spUUID, String msdUUID, Integer masterVersion) {
        try {
            log.info("Refreshing storage pool, spUUID: {} msdUUID: {}", new Object[] { spUUID, msdUUID });

            final DataCenter dataCenter = getActiveHost().getDataCenter();
            dataCenter.setMasterStorageDomainId(msdUUID);
            dataCenter.setMasterVersion(masterVersion);

            // storage into db
            setMasterDomain(spUUID, msdUUID);

            return getOKStatus();
        } catch (Exception e) {
            throw error(e);
        }
    }

    public Map getFloppyList(String spUUID) {
        Map resultMap = getOKStatus();
        resultMap.put("isolist", lst());

        return resultMap;
    }

    public Map getIsoList(String spUUID) {
        Map resultMap = getOKStatus();

        List fileList = lst();
        fileList.add("Fedora-17-x86_64-DVD.iso");

        resultMap.put("isolist", fileList);

        return resultMap;
    }

    public Map getFileList(String spUUID) {
        Map resultMap = getOKStatus();

        resultMap.put("files", lst());

        return resultMap;
    }

    public Map spmStart(String spUUID, String prevID, String prevLVER, String recoveryMode, String scsiFencing) {
        final Host host = getActiveHost();

        Map resultMap = getOKStatus();

        Task task = new Task(getUuid());
        resultMap.put("uuid", task.getId());

        task.setTarget(host);
        getActiveHost().getRunningTasks().put(task.getId(), task);
        TaskProcessor.getInstance().setTasksMap(host.getName(), task.getId());

        TaskProcessor.getInstance().addTask(new TaskRequest(TaskType.FINISH_START_SPM, 10000L, task));

        vdsmManager.setSpmMap(spUUID, host);

        return resultMap;
    }

    public Map spmStop(String spUUID) {
        final Host host = getActiveHost();

        host.setSpmId(-1);
        host.setSpmStatus(Host.SpmStatus.FREE);
        host.setSpmLver(-1);
        updateHost(host);

        Map resultMap = getOKStatus();

        vdsmManager.removeSpmFromMap(spUUID);

        return resultMap;
    }

    public Map getSpmStatus(String uuid) {
        final Host host = getActiveHost();

        Map resultMap = getOKStatus();

        Map infoMap = map();
        infoMap.put("spmId", host.getSpmId());  //1
        infoMap.put("spmStatus", host.getSpmStatus().getName()); // SPM
        infoMap.put("spmLver", host.getSpmLver()); // 0

        resultMap.put("spm_st", infoMap);

        return resultMap;
    }

    public Map createVolume(String sdUUID,
            String spUUID,
            String imgUUID,
            String size,
            Integer volFormat,
            Integer preallocate,
            Integer diskType,
            String volUUID,
            String desc,
            String srcImgUUID,
            String srcVolUUID) {
        try {
            StorageDomain storageDomain = getActiveHost().getStorageDomains().get(sdUUID);

            final Volume volume = new Volume();
            volume.setId(volUUID);
            volume.setSize(size);
            volume.setVolFormat(volFormat);
            volume.setPreallocate(preallocate);
            volume.setDiskType(diskType);
            volume.setImgUUID(imgUUID);
            volume.setDesc(desc);
            volume.setSrcImgUUID(srcImgUUID);
            volume.setSrcVolUUID(srcVolUUID);

            log.info("Adding volume: {} for sp: {}, sd: {}", new Object[] { volUUID, spUUID, sdUUID });

            storageDomain.getVolumes().put(volUUID, volume);

            final Map resultMap = getOKStatus();
            final Task task = new Task(getUuid());

            resultMap.put("uuid", task.getId());

            syncTask(vdsmManager.getSpmHost(spUUID), task);

            TaskProcessor.getInstance().addTask(new TaskRequest(TaskType.FINISH_CREATE_VOLUME, 3000L, task));

            return resultMap;
        } catch (Exception e) {
            throw error(e);
        }
    }

    public Map getVolumeInfo(String sdUUID, String spUUID, String imgGUID, String volUUID) {
        try {
            StorageDomain storageDomain = getActiveHost().getStorageDomains().get(sdUUID);

            Volume volume = storageDomain.getVolumes().get(volUUID);

            Map resultMap = getOKStatus();

            String cTime =  Long.toString(System.currentTimeMillis() / 1000);

            Map infoMap = map();
            infoMap.put("status", "OK");
            infoMap.put("domain", sdUUID); // f71ab74c-c7ae-4cdd-931b-14fb3d062076
            infoMap.put("voltype", "LEAF");
            infoMap.put("description", volume.getDesc());
            infoMap.put("parent", volume.getSrcVolUUID());
            infoMap.put("format", "RAW");
            infoMap.put("image", imgGUID); // caa6d117-75f0-4d98-98e5-f024ce3fd907
            infoMap.put("ctime", cTime);
            infoMap.put("disktype", volume.getDiskType());
            infoMap.put("legality", "LEGAL");
            infoMap.put("mtime", cTime);
            infoMap.put("apparentsize", volume.getSize());
            infoMap.put("children", lst());
            infoMap.put("capacity", volume.getSize());
            infoMap.put("uuid", volUUID); // ae745379-b417-44cc-beb9-b5d6b9d704e9
            infoMap.put("truesize", "0");
            infoMap.put("type", "SPARSE");

            resultMap.put("info", infoMap);

            return resultMap;
        } catch (Exception e) {
            throw error(e);
        }
    }

    public Map getStorageDomainInfo(String sdUUID) {
        try {
            final Host host = getActiveHost();

            StorageDomain storageDomain = host.getStorageDomains().get(sdUUID);
            DataCenter dataCenter = storageDomain.getDataCenter();

            Map resultMap = getOKStatus();

            Map infoMap = map();
            infoMap.put("uuid", storageDomain.getId());
            infoMap.put("lver", host.getSpmLver());
            infoMap.put("version", "0");
            infoMap.put("role", storageDomain.getDomainRole().getName());
            infoMap.put("remotePath", storageDomain.getConnection()); //  10.34.63.202:/mnt/export/nfs/lv1/test/iso
            infoMap.put("spm_id", host.getSpmId());
            infoMap.put("type", storageDomain.getStorageType().toString()); // NFS
            infoMap.put("class", storageDomain.getDomainClass().getName()); // Iso
            infoMap.put("name", storageDomain.getName());
            List poolList = lst();

            if (dataCenter != null) {
                infoMap.put("master_ver", dataCenter.getMasterVersion());

                poolList.add(dataCenter.getId());
                poolList.add(getUuid());
                poolList.add(getUuid()); // TODO: not sure what is it for the value, the response has 3 values, the
                                         // first is spUuid
            }

            infoMap.put("pool", poolList);
            resultMap.put("info", infoMap);

            return resultMap;
        } catch (Exception e) {
            throw error(e);
        }
    }

    public Map getStorageDomainsList(String spUUID, int domainType, int poolType, String path) {
        try {
            // spUUID, domainClass, storageType, remotePath

            Map resultMap = getOKStatus();

            List domlist = lst();

            // apply filter
            for (StorageDomain storageDomain : getActiveHost().getStorageDomains().values()) {
                if (spUUID != null && !storageDomain.getId().equals(spUUID)) {
                    continue;
                }

                if (domainType != 0
                        && StorageDomain.DomainClass.getByCode(domainType) != storageDomain.getDomainClass()) {
                    continue;
                }

                if (poolType != 0 && StorageDomain.StorageType.getByCode(poolType) != storageDomain.getStorageType()) {
                    continue;
                }

                if (path != null && !path.equals(storageDomain.getConnection())) {
                    continue;
                }

                domlist.add(storageDomain.getId());
            }

            resultMap.put("domlist", domlist);

            return resultMap;
        } catch (Exception e) {
            throw error(e);
        }
    }
    public Map deleteImage(String imgUUID,
                           String spUUID,
                           String sdUUID,
                           boolean postZero,
                           boolean force) {
        try {

            log.info("Removing volume: {}", imgUUID);

            final Map resultMap = getOKStatus();
            final Task task = new Task(getUuid());

            resultMap.put("uuid", task.getId());

            syncTask(null, task);

            TaskProcessor.getInstance().addTask(new TaskRequest(TaskType.FINISH_REMOVE_VOLUME, 5000L, task));

            return resultMap;
        } catch (Exception e) {
            throw error(e);
        }
    }
}
