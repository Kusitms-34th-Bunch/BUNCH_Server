# 알림 inbox API 계약 — #28

> 앱 내 inbox API와 [기기·개인 설정·APNs 계약](push.md)을 함께 관리한다.

[알림 Spec](notification.md)과 [알림 inbox 저장 계약](notification-schema.md)을 API로 노출하는 계약이다.
번치·번들 접근 판정은 [번들 API](bundle-api.md)와 [역할·Stage 계약](bunch-role-stage.md)의 실제 업무 규칙을 따른다.

## 1. 공통 계약

- 기본 경로는 `/api/v1`이다. 가입 완료 계정과 활성 로그인 session을 요구한다.
- 응답은 `success/data/error`, 시각은 UTC ISO-8601, nullable 필드는 JSON `null`을 사용한다. 인증·공통 오류는 [로그인·온보딩 API](login-onboarding-api.md)의 공통 계약을 따른다.
- 목록은 `page=0`, `size=20`, `size` 1~50이다. 음수 page와 범위 밖 size는 400이다. 페이지 형태는 `{items,page,size,totalElements,hasNext,unreadCount}`다.
- 정렬은 `createdAt DESC,id DESC`다. 본인·해당 번치의 30일 보관 행만 대상으로 하며, SQL에서 최신 100개를 제한한 뒤 페이지를 계산한다.
- 형식 오류는 `COMMON_001`(400), 본인 소유가 아니거나 없는 notification은 `RESOURCE_001`(404), session·가입 완료 오류는 기존 AUTH 오류를 사용한다.
- 읽음·open에는 새 Idempotency-Key나 읽음 token을 요구하지 않는다. 업무 producer의 원래 request replay는 알림을 다시 만들지 않는다.

`NotificationTarget`은 `kind,bunchId,bundleId?,noticeId?,targetBunchId?`를 가진다.
kind 호환 값은 `BUNCH_HOME`, `MEMBERS`, `ROLE_PERMISSIONS`, `DRAFT`, `DRAFT_LIST`, `BUNDLE`, `BUNDLE_COMMENTS`,
`CANDIDATES`, `OTHER_BUNCH_PROFILE`, `ACCESS_NOTICE`다. 저장된 target 자체는 접근 권한의 증거가 아니다.

## 2. type 호환성

wire enum의 번호와 이름은 저장 계약과 동일하다. 현재 #28 응답·생산자는 1~7, 9, 10이고 #51은 17~19를 실제 관계 변경에 연결한다.
8, 11~16, 20~21은 enum/storage compatibility만 유지한다. 미래 type의 producer나 fake routing은 이 API에서 만들지 않는다.

전체 enum 표는 [저장 계약의 type 호환성](notification-schema.md#type과-target-호환성)을 따른다.

문구·수신자·이동 대상은 [알림 Spec의 현재 producer 표](notification.md#3-현재-producer와-문구-계약)를 기준으로 한다.

## 3. 현재 4개 API

| 메서드·경로 | 입력 | 성공 `data` |
| --- | --- | --- |
| `GET /bunches/{bunchId}/notifications` | `page,size` | `NotificationPage` |
| `PUT /bunches/{bunchId}/notifications/{notificationId}/read` | 없음 | `{id,readAt}`; 반복해도 최초 `readAt` 유지 |
| `POST /bunches/{bunchId}/notifications/read-all` | 없음 | `{unreadCount}`; 해당 회원·번치의 보관 미확인 전체 처리 |
| `POST /bunches/{bunchId}/notifications/{notificationId}/open` | 없음 | `{notificationId,readAt,available,target}` |

`NotificationPage.items`는 `{id,type,title,body,createdAt,readAt,target}`다.
`unreadCount`는 표시 100개를 넘어선 보관 행도 포함하며, 30일 cutoff 밖의 행은 포함하지 않는다.
읽음과 목록은 회원별로 저장되어 다른 기기·다음 조회에도 반영된다.

### read / read-all

- 개별 read는 본인·번치의 보관 행만 갱신하고 이미 읽은 행의 최초 `readAt`을 보존한다.
  최초 read/open 시각은 PostgreSQL 저장 정밀도인 마이크로초로 절삭하여 첫 응답과 재조회 값이 같도록 한다.
- read-all은 같은 회원·번치·30일 cutoff의 `read_at IS NULL` 행을 한 UPDATE로 처리한다.
- 처리 후 새로 저장된 알림이나 활동은 미확인으로 남는다. 빈 목록도 성공 no-op이다.

### open

open은 먼저 읽음을 저장한 뒤 현재 대상의 존재·번치 소속·참여·공개 상태를 검사한다.
대상이 삭제됐거나 현재 접근할 수 없으면 다음처럼 200을 반환한다.

```json
{"success":true,"data":{"notificationId":801,"readAt":"2026-10-29T09:10:00Z","available":false,"target":null},"error":null}
```

읽음 저장이 성공한 뒤 이동 검사가 실패해도 읽음을 되돌리지 않는다. 읽음 저장 자체가 실패하면 이동하지 않는다.
현재 번치 홈·멤버·역할 안내·초안 목록 target은 현재 번치 소속을 확인한다. ROLE_PERMISSIONS는 별도 조회 API 없이 여는 static target이다.
초안 target은 현재 DRAFT이고 참여 중인지, 번들 target은 현재 공개 상태인지 다시 확인한다.
`OTHER_BUNCH_PROFILE`은 #51의 실제 프로필 접근 검사로 현재 수신 번치 소속·대상 존재·양방향 차단을 확인한다.
비공개 미승인도 최소 프로필은 열 수 있지만 상세 목록 권한을 부여하지 않는다.
`CANDIDATES`와 `ACCESS_NOTICE`는 실제 접근 구현이 연결될 때까지 `available:false`다.
저장 payload를 접근 권한의 근거로 사용하지 않으며 차단·삭제 후 open도 읽음 시각을 유지한다.

## 4. producer·시간 경계

- `BundleService`와 `InvitationService`는 실제 변경 성공 직후 같은 transaction에서 notification을 저장한다.
- `DRAFT_ACTIVITY`는 첫 행부터 10분 동안만 기존 행을 잠그고 합산한다. 창 종료 후에는 새 행이다.
- `COMMENT_REMINDER`는 참여 행의 24/72시간 상태를 읽고, 늦은 worker 실행 직전에 DRAFT·참여·미작성·취소 상태를 다시 확인한다.
- `DRAFT_READY_TO_PUBLISH`는 bundle의 최초 ready 사실과 원자적으로 한 번만 생성한다. 원래 BundleService request replay는 아무 notification 변화도 만들지 않는다.
- 30일 cutoff는 목록·unread·read-all·open에서 동일하며, retention 작업 전에도 적용한다.

## 5. 앱·iOS·#48 경계

앱 inbox는 기기 등록과 독립적으로 동작한다. iOS 권한·실제 APNs 수신·푸시 복귀는 앱/운영 설정을 갖춘 별도 수동 검증이다.
기기·개인 설정·payload·retry와 job wire는 [푸시 계약](push.md)을 따른다.

#48을 연결해 출시할 때는 실제 업무 변경·notification·현재 바인딩된 기기별 job을 같은 DB transaction에 저장해야 한다.
활동 합산 시 미전송 current-device job도 같은 transaction에서 갱신해야 하며, 기기가 없으면 job을 만들지 않고 나중에 등록한 기기로 과거 알림을 소급 전송하지 않는다.
