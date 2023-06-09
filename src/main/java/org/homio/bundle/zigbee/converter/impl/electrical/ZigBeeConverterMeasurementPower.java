package org.homio.bundle.zigbee.converter.impl.electrical;

import static com.zsmartsystems.zigbee.zcl.clusters.ZclElectricalMeasurementCluster.ATTR_ACTIVEPOWER;
import static com.zsmartsystems.zigbee.zcl.protocol.ZclClusterType.ELECTRICAL_MEASUREMENT;

import com.zsmartsystems.zigbee.zcl.ZclAttribute;
import com.zsmartsystems.zigbee.zcl.clusters.ZclElectricalMeasurementCluster;
import com.zsmartsystems.zigbee.zcl.clusters.ZclMeteringCluster;
import java.math.BigDecimal;
import org.homio.bundle.zigbee.converter.impl.ZigBeeConverter;
import org.homio.bundle.zigbee.converter.impl.ZigBeeInputBaseConverter;
import org.homio.bundle.api.EntityContextVar.VariableType;
import org.homio.bundle.api.state.DecimalType;


@ZigBeeConverter(name = "electrical_activepower", linkType = VariableType.Float,
                 color = "#6134CF", clientCluster = ZclElectricalMeasurementCluster.CLUSTER_ID, category = "Energy")
public class ZigBeeConverterMeasurementPower extends ZigBeeInputBaseConverter<ZclElectricalMeasurementCluster> {

    private Integer divisor;
    private Integer multiplier;

    public ZigBeeConverterMeasurementPower() {
        super(ELECTRICAL_MEASUREMENT, ATTR_ACTIVEPOWER);
    }

    @Override
    protected void afterClusterInitialized() {
        this.divisor = readAttribute(zclCluster, ZclMeteringCluster.ATTR_DIVISOR, 1);
        this.multiplier = readAttribute(zclCluster, ZclMeteringCluster.ATTR_DIVISOR, 1);
    }

    @Override
    protected void updateValue(Object val, ZclAttribute attribute) {
        Integer value = (Integer) val;
        BigDecimal valueInWatt = BigDecimal.valueOf((long) value * multiplier / divisor);
        updateChannelState(new DecimalType(valueInWatt).setUnit("W"));
    }
}
