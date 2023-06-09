package org.homio.bundle.zigbee.internal;

import static org.homio.bundle.api.util.CommonUtils.resolvePath;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.PrettyPrintWriter;
import com.thoughtworks.xstream.io.xml.StaxDriver;
import com.zsmartsystems.zigbee.IeeeAddress;
import com.zsmartsystems.zigbee.ZigBeeNode;
import com.zsmartsystems.zigbee.database.ZclAttributeDao;
import com.zsmartsystems.zigbee.database.ZclClusterDao;
import com.zsmartsystems.zigbee.database.ZigBeeEndpointDao;
import com.zsmartsystems.zigbee.database.ZigBeeNetworkDataStore;
import com.zsmartsystems.zigbee.database.ZigBeeNodeDao;
import com.zsmartsystems.zigbee.zdo.field.BindingTable;
import com.zsmartsystems.zigbee.zdo.field.NodeDescriptor.FrequencyBandType;
import com.zsmartsystems.zigbee.zdo.field.NodeDescriptor.MacCapabilitiesType;
import com.zsmartsystems.zigbee.zdo.field.NodeDescriptor.ServerCapabilitiesType;
import com.zsmartsystems.zigbee.zdo.field.PowerDescriptor.PowerSourceType;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.homio.bundle.api.EntityContext;
import org.homio.bundle.zigbee.model.ZigBeeDeviceEntity;

@Log4j2
public class ZigBeeDataStore implements ZigBeeNetworkDataStore {

  private final Path networkStateFilePath;
  private final EntityContext entityContext;
  private final String entityID;

  public ZigBeeDataStore(String networkId, EntityContext entityContext, String entityID) {
    this.networkStateFilePath = resolvePath("zigbee", networkId);
    this.entityContext = entityContext;
    this.entityID = entityID;
  }

  private XStream openStream() {
    XStream stream = new XStream(new StaxDriver());
    stream.allowTypesByWildcard(new String[]{ZigBeeNode.class.getPackage().getName() + ".**"});
    stream.setClassLoader(this.getClass().getClassLoader());

    stream.alias("ZigBeeNode", ZigBeeNodeDao.class);
    stream.alias("ZigBeeEndpoint", ZigBeeEndpointDao.class);
    stream.alias("ZclCluster", ZclClusterDao.class);
    stream.alias("ZclAttribute", ZclAttributeDao.class);
    stream.alias("MacCapabilitiesType", MacCapabilitiesType.class);
    stream.alias("ServerCapabilitiesType", ServerCapabilitiesType.class);
    stream.alias("PowerSourceType", PowerSourceType.class);
    stream.alias("FrequencyBandType", FrequencyBandType.class);
    stream.alias("BindingTable", BindingTable.class);
    stream.alias("IeeeAddress", BindingTable.class);
    stream.registerConverter(new IeeeAddressConverter());
    return stream;
  }

  private Path getIeeeAddressPath(IeeeAddress address) {
    return networkStateFilePath.resolve(address + ".xml");
  }

  @Override
  public Set<IeeeAddress> readNetworkNodes() {
    Set<IeeeAddress> nodes = new HashSet<>();
    File[] files = networkStateFilePath.toFile().listFiles();

    if (files == null) {
      return nodes;
    }

    for (File file : files) {
      if (!file.getName().toLowerCase().endsWith(".xml")) {
        continue;
      }

      try {
        IeeeAddress address = new IeeeAddress(file.getName().substring(0, 16));
        nodes.add(address);
      } catch (IllegalArgumentException e) {
        log.error("[{}]: Error parsing database filename: {}", entityID, file.getName());
      }
    }

    return nodes;
  }

  @Override
  public ZigBeeNodeDao readNode(IeeeAddress address) {
    XStream stream = openStream();

    ZigBeeNodeDao node = null;
    try {
      node = readZigBeeNodeDao(getIeeeAddressPath(address), stream);
    } catch (Exception ex) {
      log.error("[{}]: Error reading network state: {}. Try reading from backup file...", entityID, address, ex);
      try {
        node = readZigBeeNodeDao(networkStateFilePath.resolve(address + "_backup.xml"), stream);
      } catch (IOException e) {
        log.error("[{}]: Error reading network state {} from backup file", entityID, address);
        // try restore minimal node from db
        ZigBeeDeviceEntity zigBeeDeviceEntity = entityContext.getEntity(ZigBeeDeviceEntity.PREFIX + address.toString());
        if (zigBeeDeviceEntity != null && zigBeeDeviceEntity.getNetworkAddress() != 0) {
          log.warn("[{}]: Restore minimal information {}", entityID, address);
          node = new ZigBeeNodeDao();
          node.setIeeeAddress(address);
          node.setNetworkAddress(zigBeeDeviceEntity.getNetworkAddress());
        }
      }
    }

    return node;
  }

  @Override
  public void writeNode(ZigBeeNodeDao node) {
    XStream stream = openStream();
    writeZigBeeNode(node, stream, networkStateFilePath.resolve(node.getIeeeAddress() + "_backup.xml"), false);
    writeZigBeeNode(node, stream, getIeeeAddressPath(node.getIeeeAddress()), true);
  }

  private void writeZigBeeNode(ZigBeeNodeDao node, XStream stream, Path path, boolean isLog) {
    try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(path), StandardCharsets.UTF_8))) {
      stream.marshal(node, new PrettyPrintWriter(writer));
      if (isLog) {
        log.debug("[{}]: ZigBee saving network state complete. {}", entityID, node.getIeeeAddress());
      }
      // ensure writer is closed. somehow try with resources not closes writer
      writer.close(); // do not delete this!!!!!!!
    } catch (Exception e) {
      log.error("[{}]: Error writing network state: {}", entityID, node.getIeeeAddress(), e);
    }
  }

  private ZigBeeNodeDao readZigBeeNodeDao(Path path, XStream stream) throws IOException {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8))) {
      ZigBeeNodeDao nodeDao = (ZigBeeNodeDao) stream.fromXML(reader);
      reader.close();
      return nodeDao;
    }
  }

  @Override
  @SneakyThrows
  public void removeNode(IeeeAddress address) {
    if (!Files.deleteIfExists(getIeeeAddressPath(address))) {
      log.error("[{}]: Error removing network state {}", entityID, address);
    }
    Files.deleteIfExists(networkStateFilePath.resolve(address + "_backup.xml"));
  }

  /**
   * Deletes the network state file
   */
  public synchronized void delete() {
    try {
      log.debug("[{}]: Deleting ZigBee network state", entityID);
      Files.walk(networkStateFilePath).sorted(Comparator.reverseOrder()).map(Path::toFile)
           .forEach(File::delete);
    } catch (IOException e) {
      log.error("[{}]: Error deleting ZigBee network state {} ", entityID, networkStateFilePath, e);
    }
  }
}
