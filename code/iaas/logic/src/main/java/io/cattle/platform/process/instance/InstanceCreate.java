package io.cattle.platform.process.instance;

import static io.cattle.platform.core.model.tables.NicTable.*;

import io.cattle.iaas.labels.service.LabelsService;
import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.dao.GenericResourceDao;
import io.cattle.platform.core.dao.InstanceDao;
import io.cattle.platform.core.dao.LabelsDao;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Nic;
import io.cattle.platform.core.model.Volume;
import io.cattle.platform.engine.handler.HandlerResult;
import io.cattle.platform.engine.process.ProcessInstance;
import io.cattle.platform.engine.process.ProcessState;
import io.cattle.platform.json.JsonMapper;
import io.cattle.platform.object.process.ObjectProcessManager;
import io.cattle.platform.object.process.StandardProcess;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.object.util.DataUtils;
import io.cattle.platform.process.base.AbstractDefaultProcessHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.inject.Inject;
import javax.inject.Named;

@Named
public class InstanceCreate extends AbstractDefaultProcessHandler {

    @Inject
    LabelsService labelsService;
    @Inject
    GenericResourceDao resourceDao;
    @Inject
    JsonMapper jsonMapper;
    @Inject
    ObjectProcessManager processManager;
    @Inject
    LabelsDao labelsDao;
    @Inject
    InstanceDao instanceDao;

    @Override
    public HandlerResult handle(ProcessState state, ProcessInstance process) {
        setCreateStart(state);

        Instance instance = (Instance) state.getResource();

        List<String> dataVolumes = processManagedVolumes(instance);
        List<Nic> nics = objectManager.children(instance, Nic.class);

        Set<Long> nicIds = createNics(instance, nics, state.getData());

        createLabels(instance);

        HandlerResult result = new HandlerResult("_nicIds", nicIds, InstanceConstants.FIELD_DATA_VOLUMES,
                dataVolumes);
        if (shouldStart(state, instance)) {
            result.setChainProcessName(InstanceConstants.PROCESS_START);
        }

        return result;
    }

    private List<String> processManagedVolumes(Instance instance) {
        List<String> dataVolumes = DataAccessor.fieldStringList(instance, InstanceConstants.FIELD_DATA_VOLUMES);
        if (dataVolumes == null) {
            dataVolumes = new ArrayList<>();
        }
        Map<String, Object> dataVolumeMounts = DataAccessor.fieldMap(instance, InstanceConstants.FIELD_DATA_VOLUME_MOUNTS);
        if (dataVolumeMounts == null) {
            return dataVolumes;
        }
        for (Map.Entry<String, Object> mountedVols : dataVolumeMounts.entrySet()) {
            Long volId = ((Number) mountedVols.getValue()).longValue();
            Volume vol = objectManager.loadResource(Volume.class, volId);
            if (vol != null) {
                String volDescriptor = vol.getName() + ":" + mountedVols.getKey().trim();
                if (!dataVolumes.contains(volDescriptor)) {
                    dataVolumes.add(volDescriptor);
                }
            }
        }
        return dataVolumes;
    }

    protected boolean shouldStart(ProcessState state, Instance instance) {
        Boolean shouldStart = DataAccessor
                .fromDataFieldOf(state)
                .withKey(InstanceConstants.FIELD_START_ON_CREATE).as(Boolean.class);
        if (shouldStart != null) {
            return shouldStart;
        }
        return DataAccessor.fields(instance)
                .withKey(InstanceConstants.FIELD_START_ON_CREATE)
                .withDefault(true)
                .as(Boolean.class);
    }

    private void createLabels(Instance instance) {
        @SuppressWarnings("unchecked")
        Map<String, String> labels = DataAccessor.fields(instance).withKey(InstanceConstants.FIELD_LABELS).as(Map.class);
        if (labels == null) {
            return;
        }

        for (Map.Entry<String, String> labelEntry : labels.entrySet()) {
            String labelKey = labelEntry.getKey();
            String labelValue = labelEntry.getValue();

            labelsService.createContainerLabel(instance.getAccountId(), instance.getId(), labelKey, labelValue);
        }
    }

    protected Set<Long> createNics(Instance instance, List<Nic> nics, Map<String, Object> data) {
        List<Long> networkIds = populateNetworks(instance);
        return createNicsFromIds(instance, nics, data, networkIds);
    }

    protected List<Long> populateNetworks(Instance instance) {
        return DataUtils.getFieldList(instance.getData(), InstanceConstants.FIELD_NETWORK_IDS, Long.class);
    }

    protected Set<Long> createNicsFromIds(Instance instance, List<Nic> nics, Map<String, Object> data, List<Long> networkIds) {
        Set<Long> nicIds = new TreeSet<>();

        int deviceId = 0;

        if (networkIds != null) {
            for (int i = 0; i < networkIds.size(); i++) {
                Number createId = networkIds.get(i);
                if (createId == null) {
                    deviceId++;
                    continue;
                }

                Nic newNic = null;
                for (Nic nic : nics) {
                    if (nic.getNetworkId() == createId.longValue()) {
                        newNic = nic;
                        break;
                    }
                }

                if (newNic == null) {
                    newNic = objectManager.create(Nic.class, NIC.ACCOUNT_ID, instance.getAccountId(), NIC.NETWORK_ID, createId, NIC.INSTANCE_ID, instance
                            .getId(), NIC.DEVICE_NUMBER, deviceId);
                }

                deviceId++;

                processManager.executeStandardProcess(StandardProcess.CREATE, newNic, data);
                nicIds.add(newNic.getId());
            }
        }

        return nicIds;
    }

    public static boolean isCreateStart(ProcessState state) {
        Boolean startOnCreate = DataAccessor.fromMap(state.getData()).withScope(InstanceCreate.class).withKey(InstanceConstants.FIELD_START_ON_CREATE).as(
                Boolean.class);

        return startOnCreate == null ? false : startOnCreate;
    }

    protected void setCreateStart(ProcessState state) {
        DataAccessor.fromMap(state.getData()).withScope(InstanceCreate.class).withKey(InstanceConstants.FIELD_START_ON_CREATE).set(true);
    }

}
