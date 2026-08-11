# TIM 4.1.0 / 4050 Hook notes

## IPC

- Bridge ↔ Hook: TCP `127.0.0.1:18788` (abstract UDS SELinux-denied)

## Login

- `sp_login_auto` keys = current uin digits (e.g. `3086243780`)
- Account prefs: `/data/data/com.tencent.tim/shared_prefs/<uin>.xml`

## Send text (TIM 4.1.0 / QQNT)

**Do not use** `ChatActivityFacade.H0` — it only calls `J0`, and `J0` is a stub that returns an empty `long[]`. Facade “success” ≠ delivery.

### Working path (NT)

1. `QRoute.api(IMsgUtilApi).createTextElement(text)` → `MsgElement`
2. `Contact(chatType, peerUid, guildId)`  
   - C2C `chatType=1`, Group `chatType=2`  
   - C2C `peerUid` = NT uid (`IRelationNTUinAndUidApi.getUidFromUin` / `getFriendUidFromUin`)  
   - Group `peerUid` = troop uin digits
3. `QRoute.api(IMsgService).sendMsg(contact, elements, callback)`  
   **Not** `addSendMsg` — that only stages locally (message spins forever).

Key classes:

- `com.tencent.qqnt.msg.api.IMsgService`
- `com.tencent.qqnt.msg.api.IMsgUtilApi`
- `com.tencent.qqnt.kernelpublic.nativeinterface.Contact`
- `com.tencent.relation.common.api.IRelationNTUinAndUidApi`

Legacy `SessionInfo` / `QQMessageFacade` kept only as fallback.

## Recv text (MSG_IN)

Hook:

- `com.tencent.qqnt.kernel.api.impl.MsgService$getListener$1.onRecvMsg(ArrayList)`
- `com.tencent.qqnt.msg.MsgService$c.onRecvMsg(ArrayList)` (fan-out; deduped)

Parse `MsgRecord`: `chatType` 1/2, `peerUid`/`peerUin`, `senderUin`, `elements[].textElement.content`.

## chat_id (MVP)

- Friend: QQ number digits, e.g. `123456`
- Group: `troop:123456789` (troop uin)

## Contacts / groups (HELLO → `/v1/contacts` `/v1/groups`)

Friends (sync):

1. `AppRuntime.getRuntimeService(IFriendDataService).getAllFriends()`
   - fields: `uin` / `remark` / `name`
2. Fallback: `QRoute.api(IQQFriendsInfoApi).getAllFriend(scene)` + `IRelationNTUinAndUidApi` uid→uin

Groups:

1. `IKernelService.getGroupService()`
2. `addKernelGroupListener` → `getGroupList(false/true, cb)` → `onGroupListUpdate(type, ArrayList<GroupSimpleInfo>)`
   - `groupCode` → `chat_id=troop:<code>`；title = `remarkName` ?: `groupName`
3. Fallback: `ITroopManageService` EntityManager `query(TroopInfo.class)` (`troopuin` / `troopname`)
