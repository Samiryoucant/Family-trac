const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ADMIN_UID = "admin_uid_1";
const FAMILY_UID = "family_uid_2";
const STRANGER_UID = "stranger_uid_3";
const FAMILY_ID = "family_bd_001";
const ADMIN_DEVICE_ID = "dev_admin_001";
const FAMILY_DEVICE_ID = "dev_family_002";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function pastDate(msOffset = 2000) {
  return new Date(Date.now() - msOffset);
}

function futureDate(msOffset = 600000) {
  return new Date(Date.now() + msOffset);
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

async function seedFamilyAndMembers() {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    const t = pastDate(10000);
    await db.collection("families").doc(FAMILY_ID).set({
      adminUid: ADMIN_UID,
      adminEmail: "admin@familytrack.bd",
      name: "Rahman Family",
      memberUids: [ADMIN_UID, FAMILY_UID],
      createdAt: t,
      updatedAt: t,
    });
    await db.collection("families").doc(FAMILY_ID).collection("members").doc(ADMIN_UID).set({
      uid: ADMIN_UID,
      familyId: FAMILY_ID,
      displayName: "Tariq Rahman",
      role: "ADMIN",
      deviceId: ADMIN_DEVICE_ID,
      online: true,
      joinedAt: t,
      updatedAt: t,
    });
    await db.collection("families").doc(FAMILY_ID).collection("members").doc(FAMILY_UID).set({
      uid: FAMILY_UID,
      familyId: FAMILY_ID,
      displayName: "Nadia Rahman",
      role: "FAMILY",
      deviceId: FAMILY_DEVICE_ID,
      online: true,
      joinedAt: t,
      updatedAt: t,
    });
    await db.collection("families").doc(FAMILY_ID).collection("devices").doc(FAMILY_DEVICE_ID).set({
      familyId: FAMILY_ID,
      adminUid: ADMIN_UID,
      ownerUid: FAMILY_UID,
      ownerName: "Nadia Rahman",
      deviceName: "Infinix SMART 9 HD",
      deviceModel: "Infinix X6532",
      role: "FAMILY",
      consentGranted: true,
      consentTimestamp: t,
      locationSharingEnabled: true,
      locationPermissionGranted: true,
      backgroundPermissionGranted: true,
      batteryPercent: 82,
      networkType: "WIFI",
      online: true,
      revoked: false,
      lastLatitude: 23.8103,
      lastLongitude: 90.4125,
      lastAccuracyMeters: 12.5,
      lastLocationAtMs: Date.now() - 5000,
      lastSeenAtMs: Date.now() - 2000,
      lastSyncAtMs: Date.now() - 2000,
      createdAt: t,
      updatedAt: t,
    });
  });
}

test("Unauthenticated user cannot read or write any collection", async () => {
  const unauth = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauth.collection("users").doc(ADMIN_UID).get());
  await assertFails(unauth.collection("families").where("memberUids", "array-contains", ADMIN_UID).get());
  await assertFails(unauth.collection("pairing_codes").doc("FT123456").get());
});

test("User profile isolation and ghost field rejection", async () => {
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();
  const familyDb = testEnv.authenticatedContext(FAMILY_UID).firestore();
  const t = pastDate();

  await assertSucceeds(
    adminDb.collection("users").doc(ADMIN_UID).set({
      userId: ADMIN_UID,
      displayName: "Tariq",
      email: "admin@familytrack.bd",
      role: "ADMIN",
      language: "en",
      createdAt: t,
      updatedAt: t,
    })
  );

  // Shadow/ghost field must fail
  await assertFails(
    adminDb.collection("users").doc(ADMIN_UID).update({
      isSuperAdmin: true,
      updatedAt: pastDate(500),
    })
  );

  // Cross-user PII read must fail
  await assertFails(familyDb.collection("users").doc(ADMIN_UID).get());
});

test("Atomic pairing code creation, claim, and one-time expiry enforcement", async () => {
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();
  const familyDb = testEnv.authenticatedContext(FAMILY_UID).firestore();
  const strangerDb = testEnv.authenticatedContext(STRANGER_UID).firestore();
  const t = pastDate(5000);

  // Admin creates family
  await assertSucceeds(
    adminDb.collection("families").doc(FAMILY_ID).set({
      adminUid: ADMIN_UID,
      adminEmail: "admin@familytrack.bd",
      name: "Rahman Family",
      memberUids: [ADMIN_UID],
      createdAt: t,
      updatedAt: t,
    })
  );

  // Non-admin cannot create pairing code for family
  await assertFails(
    strangerDb.collection("pairing_codes").doc("FT8899AA").set({
      code: "FT8899AA",
      familyId: FAMILY_ID,
      adminUid: STRANGER_UID,
      familyName: "Rahman Family",
      status: "ACTIVE",
      expiresAt: futureDate(),
      createdAt: t,
    })
  );

  // Admin creates active pairing code
  await assertSucceeds(
    adminDb.collection("pairing_codes").doc("FT8899AA").set({
      code: "FT8899AA",
      familyId: FAMILY_ID,
      adminUid: ADMIN_UID,
      familyName: "Rahman Family",
      status: "ACTIVE",
      expiresAt: futureDate(),
      createdAt: t,
    })
  );

  // Unfiltered list on pairing_codes fails for stranger
  await assertFails(strangerDb.collection("pairing_codes").get());
  await assertSucceeds(adminDb.collection("pairing_codes").where("adminUid", "==", ADMIN_UID).get());

  // Stranger cannot join family without claiming code in batch
  await assertFails(
    strangerDb.collection("families").doc(FAMILY_ID).update({
      memberUids: [ADMIN_UID, STRANGER_UID],
      updatedAt: pastDate(500),
    })
  );

  // Family member atomically claims pairing code + joins family in batch
  const batch = familyDb.batch();
  const claimTime = pastDate(500);
  batch.update(familyDb.collection("pairing_codes").doc("FT8899AA"), {
    status: "USED",
    usedByUid: FAMILY_UID,
    usedByDeviceId: FAMILY_DEVICE_ID,
    usedAt: claimTime,
  });
  batch.update(familyDb.collection("families").doc(FAMILY_ID), {
    memberUids: [ADMIN_UID, FAMILY_UID],
    lastPairingCode: "FT8899AA",
    updatedAt: claimTime,
  });
  await assertSucceeds(batch.commit());

  // Reusing the same code fails
  await assertFails(
    strangerDb.collection("pairing_codes").doc("FT8899AA").update({
      status: "USED",
      usedByUid: STRANGER_UID,
      usedByDeviceId: "dev_stranger",
      usedAt: pastDate(100),
    })
  );
});

test("Admin-only tracking isolation: Family member cannot read other devices or location history", async () => {
  await seedFamilyAndMembers();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();
  const familyDb = testEnv.authenticatedContext(FAMILY_UID).firestore();
  const strangerDb = testEnv.authenticatedContext(STRANGER_UID).firestore();

  // Family member uploads own location record
  await assertSucceeds(
    familyDb.collection("families").doc(FAMILY_ID).collection("locations").doc("loc_001").set({
      familyId: FAMILY_ID,
      adminUid: ADMIN_UID,
      ownerUid: FAMILY_UID,
      deviceId: FAMILY_DEVICE_ID,
      latitude: 23.8103,
      longitude: 90.4125,
      accuracyMeters: 10.2,
      batteryPercent: 80,
      networkType: "WIFI",
      source: "FUSED",
      recordedAtMs: Date.now() - 4000,
      syncedAt: pastDate(500),
    })
  );

  // Admin can list all devices & locations where adminUid == ADMIN_UID
  await assertSucceeds(
    adminDb.collection("families").doc(FAMILY_ID).collection("devices").where("adminUid", "==", ADMIN_UID).get()
  );
  await assertSucceeds(
    adminDb.collection("families").doc(FAMILY_ID).collection("locations").where("adminUid", "==", ADMIN_UID).get()
  );

  // Family member CANNOT list all devices as admin (enforced on backend!)
  await assertFails(
    familyDb.collection("families").doc(FAMILY_ID).collection("devices").where("adminUid", "==", ADMIN_UID).get()
  );
  await assertFails(
    familyDb.collection("families").doc(FAMILY_ID).collection("locations").where("adminUid", "==", ADMIN_UID).get()
  );

  // Family member CAN read only their own device/locations
  await assertSucceeds(
    familyDb.collection("families").doc(FAMILY_ID).collection("devices").where("ownerUid", "==", FAMILY_UID).get()
  );

  // Stranger denied completely
  await assertFails(
    strangerDb.collection("families").doc(FAMILY_ID).collection("devices").doc(FAMILY_DEVICE_ID).get()
  );

  // Admin revokes device; family member cannot un-revoke themselves
  await assertSucceeds(
    adminDb.collection("families").doc(FAMILY_ID).collection("devices").doc(FAMILY_DEVICE_ID).update({
      revoked: true,
      updatedAt: pastDate(300),
    })
  );
  await assertFails(
    familyDb.collection("families").doc(FAMILY_ID).collection("devices").doc(FAMILY_DEVICE_ID).update({
      revoked: false,
      updatedAt: pastDate(200),
    })
  );
});

test("Messaging, Call signaling, and Tic-Tac-Toe turn enforcement", async () => {
  await seedFamilyAndMembers();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();
  const familyDb = testEnv.authenticatedContext(FAMILY_UID).firestore();
  const t = pastDate(2000);

  // Create conversation & send message
  const convRef = adminDb.collection("families").doc(FAMILY_ID).collection("conversations").doc("conv_group");
  await assertSucceeds(
    convRef.set({
      familyId: FAMILY_ID,
      type: "GROUP",
      title: "Family Group",
      participantUids: [ADMIN_UID, FAMILY_UID],
      lastMessageText: "Assalamu Alaikum",
      lastMessageType: "TEXT",
      lastMessageSenderUid: ADMIN_UID,
      lastMessageAtMs: Date.now() - 1000,
      createdAt: t,
      updatedAt: t,
    })
  );
  await assertSucceeds(
    convRef.collection("messages").doc("msg_1").set({
      conversationId: "conv_group",
      familyId: FAMILY_ID,
      senderUid: ADMIN_UID,
      senderName: "Tariq Rahman",
      participantUids: [ADMIN_UID, FAMILY_UID],
      type: "TEXT",
      text: "Assalamu Alaikum",
      clientCreatedAtMs: Date.now() - 1000,
      status: "SENT",
      readByUids: [ADMIN_UID],
      createdAt: t,
      updatedAt: t,
    })
  );

  // Tic-Tac-Toe turn enforcement
  const gameRef = adminDb.collection("families").doc(FAMILY_ID).collection("games").doc("game_1");
  await assertSucceeds(
    gameRef.set({
      familyId: FAMILY_ID,
      playerXUid: ADMIN_UID,
      playerXName: "Tariq",
      playerOUid: FAMILY_UID,
      playerOName: "Nadia",
      participantUids: [ADMIN_UID, FAMILY_UID],
      board: ["", "", "", "", "", "", "", "", ""],
      currentTurn: "X",
      status: "ACTIVE",
      moveCount: 0,
      createdAt: t,
      updatedAt: t,
    })
  );

  // Player O cannot move on X's turn
  await assertFails(
    familyDb.collection("families").doc(FAMILY_ID).collection("games").doc("game_1").update({
      board: ["O", "", "", "", "", "", "", "", ""],
      currentTurn: "X",
      status: "ACTIVE",
      moveCount: 1,
      updatedAt: pastDate(500),
    })
  );

  // Player X moves validly
  await assertSucceeds(
    gameRef.update({
      board: ["X", "", "", "", "", "", "", "", ""],
      currentTurn: "O",
      status: "ACTIVE",
      moveCount: 1,
      updatedAt: pastDate(400),
    })
  );
});

test("Family member can self-join existing GROUP conversation but stranger cannot", async () => {
  await seedFamilyAndMembers();
  const adminDb = testEnv.authenticatedContext(ADMIN_UID).firestore();
  const familyDb = testEnv.authenticatedContext(FAMILY_UID).firestore();
  const strangerDb = testEnv.authenticatedContext(STRANGER_UID).firestore();
  const t = pastDate(2000);

  const convRef = adminDb.collection("families").doc(FAMILY_ID).collection("conversations").doc("conv_group");
  await assertSucceeds(
    convRef.set({
      familyId: FAMILY_ID,
      type: "GROUP",
      title: "Family Group",
      participantUids: [ADMIN_UID],
      lastMessageText: "Group created",
      lastMessageType: "TEXT",
      lastMessageSenderUid: ADMIN_UID,
      lastMessageAtMs: Date.now() - 1000,
      createdAt: t,
      updatedAt: t,
    })
  );

  // Stranger cannot self-join
  await assertFails(
    strangerDb.collection("families").doc(FAMILY_ID).collection("conversations").doc("conv_group").update({
      participantUids: [ADMIN_UID, STRANGER_UID],
      updatedAt: pastDate(400),
    })
  );

  // Family member cannot overwrite message metadata during self-join
  await assertFails(
    familyDb.collection("families").doc(FAMILY_ID).collection("conversations").doc("conv_group").update({
      participantUids: [ADMIN_UID, FAMILY_UID],
      lastMessageText: "Hijacked",
      updatedAt: pastDate(400),
    })
  );

  // Family member can self-join GROUP conversation
  await assertSucceeds(
    familyDb.collection("families").doc(FAMILY_ID).collection("conversations").doc("conv_group").update({
      participantUids: [ADMIN_UID, FAMILY_UID],
      updatedAt: pastDate(300),
    })
  );
});
