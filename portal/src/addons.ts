// Paid add-ons: features already built into the TV apps, switched on per profile by the portal.
// The portal is the only authority; the apps never keep a "paid" flag of their own.

export const ADDON_STATUSES = ["draft", "available", "retired"] as const;
export type AddonStatus = (typeof ADDON_STATUSES)[number];

export const PURCHASE_STATUSES = ["owned", "refunded", "revoked"] as const;
export type PurchaseStatus = (typeof PURCHASE_STATUSES)[number];

/** What a TV is told about each add-on: only `active` turns its feature on. */
export type AddonState = "active" | "paused" | "available" | "not_offered";

export interface AddonRow {
  id: string;
  name: string;
  description: string;
  status: AddonStatus;
}

export interface DeviceAddon {
  id: string;
  name: string;
  state: AddonState;
}

/**
 * The add-ons a profile's TVs see. Drafts are left out. An owned add-on is active while the
 * profile is a member and paused (still owned) otherwise; one not owned, or refunded or revoked,
 * can be bought while it's available and is no longer offered once retired.
 */
export function addonStates(addons: AddonRow[], purchases: Map<string, PurchaseStatus>, member: boolean): DeviceAddon[] {
  return addons
    .filter((a) => a.status !== "draft")
    .map((a) => ({
      id: a.id,
      name: a.name,
      state: purchases.get(a.id) === "owned" ? (member ? "active" : "paused") : a.status === "available" ? "available" : "not_offered",
    }));
}

const ID = /^[a-z0-9][a-z0-9_-]{1,39}$/;

/** An add-on as the admin page saves it; [id] comes from the URL when editing one. */
export function validateAddon(v: unknown, id?: string): AddonRow {
  if (!v || typeof v !== "object") throw new Error("add-on must be an object");
  const a = v as Record<string, unknown>;
  const addonId = id ?? String(a.id ?? "").trim();
  if (!ID.test(addonId)) throw new Error("id: 2 to 40 lower-case letters, digits, - or _, starting with a letter or digit");
  const name = String(a.name ?? "").trim().slice(0, 60);
  if (!name) throw new Error("name is required");
  const status = a.status ?? "draft";
  if (!(ADDON_STATUSES as readonly unknown[]).includes(status)) throw new Error(`status must be one of ${ADDON_STATUSES.join(", ")}`);
  return { id: addonId, name, description: String(a.description ?? "").trim().slice(0, 300), status: status as AddonStatus };
}
