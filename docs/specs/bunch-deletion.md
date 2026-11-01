# 번치 삭제·접근 불가 안내 구현 기준 (#50)

이 문서는 `BUNCH-09`, `BUNCH-04`, `NOTI-02`를 실제 서버 기능으로 연결하기 위한
구현 기준과 검증 시나리오다. 제품 문구·API·DB의 원문 기준은 `bunch.md`,
`bunch-api.md`, `bunch-schema.md`, `photo-foundation.md`, `notification*.md`를
우선한다.

## 1. 현재 기준과 선행 게이트

작업 브랜치는 `origin/feat/28-notification-foundation`의 `8fbf45a`에서 시작한다.
현재 브랜치에는 실제 선행 migration V14~V17까지 병합되어 있으며, 아래 선행
산출물의 소유 파일을 복제해 임시 테이블·adapter·범용 이벤트·빈 service를
만들지 않는다.

| 선행 | 이 작업에서 재사용할 실제 산출물 | 없을 때의 처리 |
| --- | --- | --- |
| #25 `feat/25-bunch-membership-exit` (`fcdd435`, `d022c7c`) | V17 `AccessNotice` entity/repository/API와 이탈 정리 callback 경계 | 문서·시나리오만 준비하고 안내 API를 복제하지 않음 |
| #49 `feat/49-bunch-member-admin` (`4ab0020`) | V16 `bunch_action_requests` entity/repository와 30일 성공 결과 저장 계약 | 임시 멱등성 테이블·범용 request adapter를 만들지 않음 |
| #51 `feat/51-bunch-social` (`9116ee6`) | V14 실제 양방향 follow/block/report 관계와 삭제 repository 경계 | 가짜 소셜 테이블이나 native SQL skeleton을 만들지 않음 |
| #48 `feat/48-bunch-push` (`9cac1ad`, `958ae52`) | 기기별 `push_jobs` 저장/전송 계약 | inbox·임시 큐·가짜 push bridge를 만들지 않음 |
| #57 `origin/feat/28-notification-foundation` (`8fbf45a`) | `NotificationService.readAt` PostgreSQL microsecond 보정 | 최종 검증 전에 fetch 후 해당 SHA 포함 여부를 확인 |

선행 산출물의 소유 파일은 수정하지 않는다. 특히 `BundleService`,
`BundleRealtime*`, `BunchService`, `InvitationService`, 이탈/공유 service,
AccessNotice API는 이 기능의 소유 범위가 아니다. 필요한 callback·메서드·락
연계가 없으면 부모 통합 항목으로 보고한다.

## 2. 공개 계약

### 삭제 요청

```http
POST /api/v1/bunches/{bunchId}/deletion
Idempotency-Key: <UUID>
Content-Type: application/json
```

```json
{
  "actorMembershipId": 100,
  "expectedMembershipVersion": 12,
  "confirmation": "삭제하기"
}
```

- principal의 회원, `actorMembershipId`의 현재 ACTIVE Membership, `OWNER` 역할,
  대상 `membershipVersion`을 서버가 모두 재검증한다.
- `confirmation.trim()`이 정확히 `삭제하기`인 경우만 실행한다.
- 같은 회원·번치·작업·UUID는 성공 결과를 30일 보존하고 최소 결과만 재생한다.
  같은 키에 다른 actor membership, 기대 버전, 확인 문구를 보내면
  `REQUEST_001`이며 삭제·안내·푸시를 새로 만들지 않는다.
- 기존 번치가 이미 없어도 성공 기록이 있는 같은 키는 최소 결과를 재생한다.
  새 키는 현재 권한을 검증하고 삭제된 본체를 복구하지 않는다.
- 성공 결과의 `defaultBunchId`는 저장하지 않고 응답 시점의 현재 ACTIVE 소속을
  `lastUsedAt DESC, id DESC`로 다시 계산한다. 없으면 `null`이다.

### 접근 불가 안내

삭제 전 현재 ACTIVE Membership 전체에서 삭제자를 제외한 회원의 ID, 당시 번치명,
character type, 참여 회차를 snapshot으로 확보한다. 각 수신자에게 실제 #25의
`BunchAccessNotice`/`BunchAccessNoticeRepository`를 사용해 `reason=DELETED`,
`occurredAt`, `expiresAt=+30일`을 삭제 service가 같은 transaction에서 직접 저장한다.
삭제자에게는 안내를 만들지 않는다.

- AccessNotice는 `bunch_id` FK와 이미지 FK를 갖지 않으며, 번치 본체 삭제와
  독립적으로 남는다. 안내 화면은 저장된 character type으로 기본 캐릭터를
  표시하고 삭제된 번치의 profile/image asset을 참조하지 않는다.
- 수신자는 앱 inbox notification 행이 아니라 접근 안내로만 알린다.
- 실제 #48 push job이 존재하는 경우에만 당시 수신자의 현재 기기별 payload를
  같은 DB transaction에서 `type=15/BUNCH_DELETED` push job으로 저장한다. 기기가
  없으면 job을 만들지 않고, 이후 등록으로 소급하지 않는다.
- 푸시 권한 거부·APNs 실패·외부 전송 실패가 업무 transaction을 rollback시키지
  않도록 저장과 전송을 분리한다. 저장 실패는 업무와 함께 rollback한다.
- 확인 DELETE 또는 30일 만료는 #25 안내 API의 소유 범위다. 이 기능은 안내
  조회·확인·만료 API를 중복 구현하지 않는다.

## 3. 정리 계획

### 3.1 Transaction 내부

1. 기존 caller의 실제 lock graph를 기준으로 삭제자 회원 row를 먼저 잠근 뒤 대상
   번치 row를 잠근다. 이후 수신자 member/device row를 잠그지 않고, 번치 아래
   bundle/photo/lease를 ID 오름차순으로 처리한다. 현재 코드의 근거는 다음과 같다.

   - `BundleAccessService`/`BundleService`와 `PhotoService`는 `bunches.findLocked`
     후 bundle/photo/lease를 잠근다.
   - `PhotoUploadService`는 upload-batch advisory lock → bunch row lock →
     non-locking member 조회 → membership/photo 잠금 순서다.
   - `BunchService` 프로필/last-used와 `#49` 관리 service는 member row lock 후
     bunch row lock을 잡는 경로가 있어, 삭제 service가 여러 member row를
     선점하는 순서를 새로 만들면 안 된다.
   - #25의 안내 저장/조회 자체는 bunch row를 잠그지 않으며, 실제 이탈 callback의
     최종 commit graph는 최초 commit 후 다시 대조한다.

   따라서 삭제는 삭제자 member → 단일 bunch row lock으로 같은 번치의
   bundle/photo/관리 변경을 직렬화하고, 수신자 member/device row를 임의 순서로
   잠그지 않는다. 선행 branch의
   최종 lock graph와 함께 삭제·leave/remove·owner-transfer·publish·upload 경합을
   실제 PostgreSQL에서 deadlock 없이 검증한다.
2. 성공 request replay를 확인한 뒤 fingerprint와 요청 입력을 비교한다.
3. `OWNER`와 actor membership, 기대 `membershipVersion`, 정확한 active 상태를
   다시 검증한다. 실패하면 어떠한 삭제 row도 변경하지 않는다.
4. 삭제자 외 활성 멤버의 안내·push snapshot, 번치 프로필 asset ID, Photo asset ID,
   발행 `bundle_photos.image_asset_id`를 먼저 수집한다. ImageAsset은 ID만 수집하고
   공유 여부는 restrictive FK와 commit 후 정리에 맡긴다.
5. 현재 실제 모델의 repository와 callback을 사용해 다음 순서로 하위 관계를
   명시적으로 정리한다. DB cascade가 callback·lease·registry·파일 정리를
   대신한다고 가정하지 않는다.

   - 초대, 알림/리마인드의 번치 소유 행, draft·published Bundle의 참여·코멘트·사진,
     bundle request 중 번치 FK를 가진 행, bundle edit lease
   - Photo uploader와 Photo, 번치 프로필 참조 및 번치 소속/역할/종료 이력
   - #51의 실제 양방향 follow/block/report 관계
   - 번치 본체
   - 기존 `notifications`는 번치 FK cascade로 제거하고, pending comment reminder
     push job은 #48 `PushJobWriter`를 통해 취소한다. 삭제 안내 type 15 job은
     기기별 snapshot을 새로 저장한다.
   - #49 `bunch_action_requests` 성공 행과 #25 AccessNotice 및 #48 push job은
     번치 FK 없이 보존되도록 먼저 결과를 저장하고 본체 cascade 대상에서 제외

6. 사진 최종 삭제 callback이 발행본 참조를 null/보존하고 초안 참조를 정리하는
   기존 계약을 지키는지 검증한다. 모든 실제 참조가 해제된 asset에만
   `ImageAssetService.releaseAfterCommit(assetId)`를 등록한다.
7. 최소 삭제 결과를 #49의 `BunchActionRequest`/
   `BunchActionRequestRepository`에 같은 transaction으로 직접 저장한다. #49의
   `Map<String, Any?>` result shape와 `expiresAt=+30일`을 재사용하고 entity/repository
   계약은 수정하지 않는다.
8. DB lease row는 번치 삭제 transaction 안에서 명시적으로 release하고, 외부 파일은
   `releaseAfterCommit`으로 commit 뒤에만 정리한다. 현재 선행 `BundleRealtime*`에는
   번치별 registry enumeration/removal hook이 없으므로 해당 in-memory registry 제거는
   부모 #59 통합 항목으로 남긴다. 대신 commit 후 권한 검증은 삭제된 번치/번들 상태를
   다시 확인해 열린 socket의 command/subscription 발신을 차단한다.

### 3.2 소유 범위 밖 연계 보고

다음 중 하나가 현재 선행 API에 없으면 해당 파일을 직접 수정하지 않고 부모 통합
항목으로 보고한다.

- 번치 삭제를 위한 `AccessNotice` 생성/확인/만료 호출
- action request fingerprint/replay 저장 및 30일 만료 정책
- push job의 기기별 snapshot 저장
- 소셜 양방향 관계 삭제
- 열린 realtime session을 번치 삭제 commit 후 접근 차단하는 registry hook
- `PhotoFinalDeletionCallback` 내부의 Bundle 정리와 발행 이미지 보존

## 4. 통합 테스트 시나리오

테스트는 실제 PostgreSQL `localhost:55450`, Redis `localhost:56450`,
`bunch_issue50_test`에서 실행한다. fixture cleanup은 이 DB에만 수행하며 운영
DB·공유 Redis key를 사용하지 않는다.

### 입력·권한·멱등성

- MEMBER/CO_OWNER, 다른 번치의 membership, 종료된 membership, actor ID 불일치,
  가입 미완료 회원은 아무 row도 삭제하지 않고 권한 오류를 반환한다.
- owner가 아닌 actor, 없는 번치, 없는 membership, 잘못된 expected version,
  `삭제하기` 앞뒤 공백을 제외한 오탈자는 삭제하지 않는다.
- 같은 UUID의 첫 성공·동일 입력 재생은 한 번만 삭제·안내·push를 만들고 같은
  최소 결과를 반환한다.
- 같은 UUID의 다른 body/fingerprint, 30일 밖의 성공 기록, 새 UUID 재시도는
  각각 계약된 충돌/권한 검증을 적용하며 본체 복구나 중복 효과를 만들지 않는다.
- 삭제자가 아닌 수신자에게만 DELETED AccessNotice와 기기별 push job이 있고,
  `notifications` inbox 행이나 삭제자 안내는 없다.

### 원자 정리·참조 보존

- 프로필 asset, 사진함 Photo·uploader, draft/published bundle의 photo/caption/
  participant/comment/reminder/lease, 초대, 번치 소속, #51의 양방향 social FK가
  실제 모델 기준으로 모두 정리된다.
- 발행 bundle이 공유하는 ImageAsset, 다른 회원/번치가 참조하는 ImageAsset,
  발행본의 photo가 참조하는 asset은 보존된다. 삭제 전 수집한 asset만
  `releaseAfterCommit` 대상으로 등록된다.
- Photo 최종 삭제 callback 또는 notification/notice/action/push 저장 실패를
  주입하면 번치·소속·하위 데이터·안내·요청 결과가 모두 rollback된다.
- 외부 storage delete가 실패해도 이미 커밋된 DB 업무와 접근 안내는 rollback하지
  않으며, 실패한 public ID만 기존 정리 경계에 남는다.
- 삭제 성공 후 request 결과·AccessNotice·push job은 본체 FK 없이 남고, 새
  `defaultBunchId`는 현재 활성 소속에서 즉시 재계산된다.

### 경합·접근 차단

- 삭제와 leave/remove/owner-transfer/publish/upload가 같은 번치에 동시에 들어와도
  삭제는 위에서 확인한 실제 lock graph에 맞춰 bunch row를 직렬화하고, 새 member
  다중 잠금 순서를 만들지 않는다. 한쪽의 전체 transaction만 성공하고 deadlock
  timeout 없이 종료하는 것을 실제 PostgreSQL에서 확인한다.
- 삭제 중 이미 시작한 upload/publish/인계는 번치 권한·상태 재검증에서 차단되고,
  먼저 성공한 업무는 자기 원자성 규칙을 유지한다.
- 삭제 후 재생·재조회·새 업로드·새 bundle 접근·STOMP command/subscription은
  서버 권한 검증에서 거부된다. 발신 registry 정리 전후 모두 접근 차단을 유지한다.
- lease가 존재하는 draft를 삭제한 뒤 lease row가 정리되고, 만료 lease가 새 접근을
  허용하는 우회가 없다. registry/session 제거 hook은 #59 부모 통합에서 실제 연결한다.
- AccessNotice는 본인 수신자만 조회/확인할 수 있고, `LEFT`/`REMOVED` 번치에
  새 참여 회차가 생긴 경우에도 과거 notice를 혼동하지 않는다. 영구 삭제된
  번치에는 새 참여 회차가 생기지 않는다. 확인은 200 no-op 또는 성공 후 목록에서
  제거하고, 만료 notice는 접근할 수 없다.

### 보존 기간·재생·수동 검증

- AccessNotice와 성공 action request의 30일 경계(`now`, `now-30d`, `now-30d-ε`),
  push job 24시간 전송 만료와 30일 job 정리를 검증한다.
- `LEFT`/`REMOVED` 상태의 기존 소속만 유효한 초대와 새 참여 키로 새 `MEMBER`
  회차를 만들 수 있다. 영구 삭제된 번치는 초대·조회·재참여로 복구할 수 없고,
  삭제된 번치의 삭제 데이터·권한·사진함·초안을 복구하는 계약이나 API를 추가하지
  않는다. 남아 있는 발행 기록의 현재 member profile 재계산은 삭제되지 않은
  번치의 기존 재참여 계약에만 적용한다.
- 실제 iOS WebView 모달, 기기별 APNs 권한·전달·앱 복귀·열린 socket 종료는 자동
  통합 테스트와 분리해 환경이 준비된 뒤 수동 검증한다. 환경이 없으면 완료 보고에
  미검증으로 남긴다.

## 5. 완료 조건

- #25/#49/#51/#48 실제 커밋을 fetch/merge한 뒤에만 신규 service/controller/DTO/
  통합 테스트와 필요한 schema 변경을 구현한다.
- 삭제에 별도 schema 변경이 실제로 필요할 때만 적용된 migration을 수정하지 않고
  실제 선행 migration 번호 뒤에 V18을 추가한다. 기존 삭제 SQL·domain만으로 계약을
  충족하면 빈 migration은 만들지 않는다.
- 변경된 파일의 diagnostics, 관련 통합 테스트, Flyway 재실행/JPA validate,
  `DB_URL=jdbc:postgresql://localhost:55450/bunch_issue50_test` 전체 build를
  실행한다.
- 최종 검증 직전에 `origin/feat/28-notification-foundation`을 fetch하고
  `8fbf45a`가 포함된 상태를 확인한다.
- 기기/APNs 수동 검증은 자동 테스트 통과와 별도로 보고한다.

## 6. 현재 구현·통합 상태

- 신규 소유 파일은 `BunchDeletionController`, `BunchDeletionRequest`,
  `BunchDeletionService`, `BunchDeletionIntegrationTest`다. V18은 추가하지 않았다.
  V1~V17의 기존 SQL/도메인만으로 삭제 cascade·독립 안내·요청 결과 계약을 충족한다.
- 실제 통합 테스트 4개는 isolated PostgreSQL/Redis에서 통과했다. 테스트 실행 시
  #48 `PushDevice.tokenHash`의 JPA `char(64)`/Flyway `CHAR(64)` 타입 불일치 때문에
  `SPRING_JPA_HIBERNATE_DDL_AUTO=none`을 명시했으며, 정상 `validate`는 부모 #48
  수정 후 재실행해야 한다.
- 열린 realtime registry의 commit 후 제거는 기존 `BundleRealtime*`를 수정하지 않고
  부모 #59에서 hook을 연결해야 한다. 삭제 후 DB 권한 검증 및 발신 frame 차단 자체는
  기존 interceptor 경로를 재사용한다.
