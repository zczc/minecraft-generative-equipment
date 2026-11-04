package dev.generative.equipment.paper.adapters;

import static dev.generative.equipment.core.RuntimeTypes.*;
import static dev.generative.equipment.protocol.JsonContracts.MAPPER;

import com.fasterxml.jackson.databind.JsonNode;
import dev.generative.equipment.core.*;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** 只在主线程访问实际主手槽位。状态随原版物品存档保存，不维护所有者数据库。 */
public final class PdcItemStateAdapter implements HostPorts.ItemStatePort {
  public static NamespacedKey key(String name) {
    return new NamespacedKey("ge", name);
  }

  public static String text(ItemStack item, String name) {
    var meta = item.getItemMeta();
    return meta == null
        ? null
        : meta.getPersistentDataContainer().get(key(name), PersistentDataType.STRING);
  }

  public static long number(ItemStack item, String name) {
    var meta = item.getItemMeta();
    return meta == null
        ? 0
        : meta.getPersistentDataContainer().getOrDefault(key(name), PersistentDataType.LONG, 0L);
  }

  public static boolean generated(ItemStack item) {
    return item != null && !item.isEmpty() && text(item, "definition_id") != null;
  }

  public static String reference(ItemStack item) {
    return text(item, "definition_id") + ":" + number(item, "definition_version");
  }

  private final java.util.function.Predicate<ActivationLease> active;

  public PdcItemStateAdapter(java.util.function.Predicate<ActivationLease> active) {
    this.active = active;
  }

  private ItemStack current(ActivationLease lease) {
    if (!Bukkit.isPrimaryThread() || !active.test(lease))
      throw new IllegalStateException("Inactive item or wrong thread");
    var p = Bukkit.getPlayer(lease.playerId());
    if (p == null) throw new IllegalStateException("Player offline");
    var item = p.getInventory().getItemInMainHand();
    if (!generated(item)
        || !Objects.equals(text(item, "definition_id"), lease.definition().definitionId())
        || number(item, "definition_version") != lease.definition().version()
        || item.getAmount() != 1) throw new IllegalStateException("Item changed");
    return item;
  }

  public ItemSnapshot read(ActivationLease lease) {
    var item = current(lease);
    try {
      return new ItemSnapshot(
          number(item, "state_revision"),
          MAPPER.readTree(Objects.requireNonNull(text(item, "state_json"))));
    } catch (Exception e) {
      throw new IllegalStateException("Corrupt item state", e);
    }
  }

  public WriteResult write(ActivationLease lease, long expected, JsonNode state) {
    var item = current(lease);
    if (number(item, "state_revision") != expected)
      return new WriteResult(false, expected, "STATE_CONFLICT");
    try {
      var bytes = MAPPER.writeValueAsBytes(state);
      if (bytes.length > 32768) return new WriteResult(false, expected, "STATE_LIMIT");
      var meta = item.getItemMeta();
      var pdc = meta.getPersistentDataContainer();
      pdc.set(
          key("state_json"),
          PersistentDataType.STRING,
          new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
      pdc.set(key("state_revision"), PersistentDataType.LONG, expected + 1);
      item.setItemMeta(meta);
      Bukkit.getPlayer(lease.playerId()).getInventory().setItemInMainHand(item);
      return new WriteResult(true, expected + 1, null);
    } catch (Exception e) {
      throw new IllegalStateException("Could not persist actual item state", e);
    }
  }

  public static ItemStack create(LoadedDefinition def, String deliveryId) {
    var material = Material.matchMaterial(def.equipment().path("baseItem").asText());
    if (material == null) throw new IllegalArgumentException("Unknown base item");
    var item = new ItemStack(material);
    var meta = item.getItemMeta();
    meta.setMaxStackSize(1);
    meta.displayName(Component.text(def.equipment().path("name").asText()));
    List<Component> lore = new ArrayList<>();
    lore.add(Component.text(def.equipment().path("description").asText()));
    for (var c : def.equipment().path("constraints"))
      lore.add(Component.text(c.path("text").asText()));
    lore.add(Component.text("右键使用 · /ge inspect · /ge records"));
    meta.lore(lore);
    var pdc = meta.getPersistentDataContainer();
    pdc.set(key("format_version"), PersistentDataType.INTEGER, 1);
    pdc.set(key("definition_id"), PersistentDataType.STRING, def.program().ref().definitionId());
    pdc.set(key("definition_version"), PersistentDataType.LONG, def.program().ref().version());
    pdc.set(key("delivery_id"), PersistentDataType.STRING, deliveryId);
    pdc.set(key("provenance_id"), PersistentDataType.STRING, def.program().ref().definitionId());
    pdc.set(key("state_revision"), PersistentDataType.LONG, 0L);
    pdc.set(
        key("state_json"),
        PersistentDataType.STRING,
        def.equipment().path("initialState").toString());
    item.setItemMeta(meta);
    return item;
  }
}
