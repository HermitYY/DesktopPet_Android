package com.study.desktoppet;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable authoritative state sent by the currently selected Unity pet. */
final class PetAccessoryState {
    static final PetAccessoryState DISABLED = new PetAccessoryState(false, "", Collections.emptyList());
    final boolean enabled;
    final String session;
    final List<Slot> slots;

    private PetAccessoryState(boolean enabled, String session, List<Slot> slots) {
        this.enabled = enabled;
        this.session = session;
        this.slots = Collections.unmodifiableList(slots);
    }

    static final class Slot {
        final int id;
        final String name;
        final List<Item> items;
        Slot(int id, String name, List<Item> items) {
            this.id = id;
            this.name = name;
            this.items = Collections.unmodifiableList(items);
        }
    }

    static final class Item {
        final int id;
        final String name;
        final boolean selected;
        Item(int id, String name, boolean selected) {
            this.id = id;
            this.name = name;
            this.selected = selected;
        }
    }

    Item findItem(int slotId, int itemId) {
        for (Slot slot : slots)
            if (slot.id == slotId)
                for (Item item : slot.items)
                    if (item.id == itemId) return item;
        return null;
    }

    static PetAccessoryState fromJson(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        if (!root.optBoolean("enabled", false)) return DISABLED;
        String session = root.optString("session", "");
        if (session.isEmpty()) return DISABLED;
        List<Slot> slots = new ArrayList<>();
        JSONArray sourceSlots = root.optJSONArray("slots");
        if (sourceSlots != null) for (int i = 0; i < sourceSlots.length(); i++) {
            JSONObject slot = sourceSlots.getJSONObject(i);
            int slotId = slot.getInt("id");
            if (slotId < 0) throw new JSONException("Negative accessory position id");
            List<Item> items = new ArrayList<>();
            JSONArray sourceItems = slot.optJSONArray("items");
            if (sourceItems != null) for (int j = 0; j < sourceItems.length(); j++) {
                JSONObject item = sourceItems.getJSONObject(j);
                int itemId = item.getInt("id");
                if (itemId < 0) throw new JSONException("Negative accessory item id");
                items.add(new Item(itemId, item.optString("name", "饰品"), item.optBoolean("selected", false)));
            }
            slots.add(new Slot(slotId, slot.optString("name", "位置"), items));
        }
        return new PetAccessoryState(true, session, slots);
    }
}
