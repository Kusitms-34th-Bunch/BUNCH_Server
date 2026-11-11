# 개인 푸시·APNs 계약 — #48

[알림 Spec](notification.md)·[inbox API](notification-api.md)·[저장 계약](notification-schema.md)의 기기별 전송 계약이다.
기능·테스트·V15 migration과 함께 관리한다. 앱 내 알림은 개인 푸시 설정과 독립적이다.
푸시 설정은 이슈 #109(V35)에서 종류별 21개가 아니라 [4개 카테고리](#push-preferences)로 바뀌었다.

## API

기본 경로 `/api/v1`, 활성 로그인 session·가입 완료 계정, `success/data/error`, UTC ISO-8601을 사용한다.

| 메서드·경로 | 요청 | 성공 data |
| --- | --- | --- |
| `PUT /members/me/push-devices/{installationId}` | `{deviceToken,environment,authorizationStatus}` | `{installationId,environment,authorizationStatus,registeredAt}` |
| `DELETE /members/me/push-devices/{installationId}` | 없음 | `null` |
| `GET /members/me/push-preferences` | 없음 | `{enabled,categories}` ([상세](#push-preferences)) |
| `PATCH /members/me/push-preferences` | `{enabled?,categories?}` | `{enabled,categories}` |

`installationId`는 설치별 UUID다. 토큰은 APNs Data를 hex로 인코딩한 짝수 길이 2~4096자(1~2048 bytes)이며,
32 bytes로 고정하지 않는다. 대문자 입력도 소문자로 정규화한다. 토큰은 응답에 포함하지 않는다.
환경은 `SANDBOX`/`PRODUCTION`, 권한은 `NOT_DETERMINED`/`DENIED`/`AUTHORIZED`/`PROVISIONAL`이다.
topic과 endpoint는 서버 설정을 사용하며 앱이 지정하지 않는다. 해당 환경의 topic이 없으면 400이다.

- 앱 시작·로그인·토큰 콜백·권한 변경 때 같은 설치에 PUT한다. 같은 귀속·토큰·권한이면 no-op이며 기존 pending job을 보존한다.
- 다른 회원/sid 또는 새 설치로 같은 환경/topic/token을 등록하면 이전 귀속의 미전송 job을 취소하고 현재 바인딩으로 교체한다.
- DELETE는 본인·현재 sid 소유만 해제한다. 이미 없으면 성공, 다른 귀속이면 404 `RESOURCE_001`이다.
- 로그아웃은 현재 sid의 기기와 pending job만 해제·취소한다. 다른 sid는 유지한다.
  등록·해제·로그아웃은 회원 잠금 아래 활성 session을 다시 확인한다.
- 푸시 설정의 값·규칙·오류는 [알림 설정](#push-preferences)을 따른다.
- 형식·enum·토큰 오류는 400, 비활성 session은 401, 미가입 완료는 기존 가입 오류다.
  `enabled`와 카테고리 설정은 inbox 생성·읽음에 영향을 주지 않는다. 알림 읽음만으로 pending push를 취소하지 않는다.

```json
{"deviceToken":"a1b2c3d4","environment":"SANDBOX","authorizationStatus":"AUTHORIZED"}
```

```json
{"enabled":true,"categories":{"BUNDLE_NEWS":true,"MEMBER_ROLE":true,"FOLLOW":false,"REACTION":true}}
```

<a id="push-preferences"></a>
## 알림 설정 — 이슈 #109

기준은 마이 기능명세서 7.3.1~7.3.4이며, 메인 명세서 5.12.3 로직 3이 "푸시는 사용자의 알림 설정을 따른다(MY 명세 범위)"로
이 설정에 위임한다. 설정은 번치별이 아니라 **계정 단위**이고 모든 기기에 같이 적용된다. 푸시에만 적용하며 앱 안 알림(inbox)은
설정과 관계없이 그대로 쌓인다. 기기 자체의 알림 권한(`authorizationStatus`)은 설정과 별개로 각 기기 기준으로 확인한다.

| 카테고리 | 화면 제목(7.3.3) | 알림 번호 | 알림 종류 |
| --- | --- | --- | --- |
| `BUNDLE_NEWS` | 번들 소식 | 1~8 | `DRAFT_PARTICIPANT_ADDED`, `DRAFT_ACTIVITY`, `COMMENT_REMINDER`, `DRAFT_PARTICIPANT_REMOVED`, `DRAFT_READY_TO_PUBLISH`, `DRAFT_PARTICIPANTS_INSUFFICIENT`, `BUNDLE_PUBLISHED`, `CANDIDATE_CREATED` |
| `MEMBER_ROLE` | 멤버 및 권한 소식 | 10~13 | `MEMBER_JOINED`, `CO_OWNER_GRANTED`, `CO_OWNER_REVOKED`, `OWNER_TRANSFERRED` |
| `FOLLOW` | 팔로우 | 17~19 | `BUNCH_FOLLOWED`, `FOLLOW_REQUESTED`, `FOLLOW_REQUEST_APPROVED` |
| `REACTION` | 좋아요 및 댓글 | 20~22 | `SOCIAL_COMMENT_CREATED`(20·22), `BUNDLE_LIKED` |

- **응답:** `enabled`(앱 푸시 알림)와 네 카테고리의 현재 값을 항상 모두 준다. 마스터가 꺼져 있어도 카테고리는 저장된 원래 값을
  주므로 앱이 "비활성이지만 켜짐/꺼짐"을 구분해 보여줄 수 있다(7.3.1). 저장된 값이 없으면 전부 `true`다.
- **PATCH:** 받은 항목만 바꾸는 병합 저장이며 같은 요청을 다시 보내면 같은 결과다. 토글마다 즉시 저장한다(7.3.2·7.3.3).
  같은 계정의 동시 PATCH는 회원 잠금으로 직렬화해 서로 다른 토글을 모두 보존한다. 미지의 카테고리는 400 `COMMON_001`
  (알림 종류 이름도 카테고리가 아니므로 같다), 문자열·숫자·null 값은 400 `COMMON_002`다. 예전 `types` 필드는 무시한다.
- **마스터 끄기·켜기(7.3.2):** 끄면 모든 푸시를 보내지 않지만 카테고리 값은 지우지 않는다. 다시 켜면 끄기 전 값 그대로다.
  마스터가 꺼진 동안 도착한 카테고리 PATCH도 값은 저장하고 마스터는 그대로 둔다(앱은 이때 토글을 막지만 서버는 연타·지연
  요청을 실패시키지 않는다).
- **자동 변경 없음(7.3.3 상세 3):** 「앱 푸시 알림」은 사용자가 직접 눌렀을 때만 바뀐다. 네 카테고리를 모두 꺼도 서버는 `enabled`를
  바꾸지 않고, 마스터를 껐다 켜도 카테고리 값을 건드리지 않는다. 서버는 요청에 담긴 값만 저장하며 마스터와 카테고리는
  서로 영향을 주지 않는다. 네 카테고리가 모두 꺼진 채 마스터가 켜져 있으면 항상 발송 종류(14~16번)만 푸시로 받는다.
- **항상 발송(7.3.2 로직 4):** 내보내짐(14번 `MEMBER_REMOVED`), 번치 삭제(15번 `BUNCH_DELETED`), 번치 공개 범위 바뀜
  (16번 `BUNCH_VISIBILITY_CHANGED`)은 카테고리가 없고 개별로 끌 수 없다. 마스터가 켜져 있으면 항상 보내고, 마스터나 기기 자체의
  알림 권한이 꺼져 있으면 보내지 않는다.
- **카테고리가 없는 종류:** 푸시하지 않는다. 메인 명세서 5.12.3 9번(뭉치 성장)이 삭제되어 `CHARACTER_GROWN`이 여기에 해당한다.
  이 알림을 **만드는** 코드는 메인(번들) 담당 범위라 이 변경에서 건드리지 않았고, 인박스에는 남지만 푸시는 나가지 않는다.
- **적용 시점:** 알림을 만들 때(job 생성)와 전송 직전 두 번 같은 판단(`PushPreference.allows`)으로 확인한다. 설정을 끈 뒤에는
  이미 대기 중이던 job도 `CANCELLED(RecipientUnavailable)`로 취소된다. 설정 행이 없으면 모든 카테고리를 받는다.
- **권한이 꺼져도(7.3.4):** 앱 안 설정값은 바꾸거나 지우지 않는다. 기기 등록 요청이 설정 행을 만들거나 바꾸지 않는다.
- **최초 가입 시 초기값(7.3.1 로직 3):** 알림을 허용했으면 모두 켜짐(저장된 값이 없을 때의 기본값과 같다), 거부했으면 모두 꺼짐이다.
  거부한 사용자는 기기 토큰이 없어 서버에 기기를 등록할 수 없으므로, 앱이 가입 직후 권한 결과로 `PATCH`를 한 번 보낸다.
  거부면 `{"enabled":false,"categories":{"BUNDLE_NEWS":false,"MEMBER_ROLE":false,"FOLLOW":false,"REACTION":false}}`다.
  서버는 카테고리를 모두 꺼도 마스터를 자동으로 끄지 않으므로 **`enabled:false`를 반드시 함께 보내야** 한다.
  서버가 권한 결과를 직접 받는 방식은 이 이슈에서 만들지 않았다(앱 담당과 확인 필요).

**호환성:** 응답·요청의 `types`(21개 알림 종류) 필드가 사라지고 `categories`로 바뀐다. 이전 `types`로 연동한 앱은 맞춰야 한다.
V35는 `categories` 컬럼을 추가하고 기존 종류별 값을 이관한다. 한 카테고리의 알림 종류 중 하나라도 꺼져 있었으면 그 카테고리를 끈다.
`enabled`는 바꾸지 않고, 항상 발송하는 16번(`BUNCH_VISIBILITY_CHANGED`)의 예전 값은 이관하지 않는다. 예전 `types` 컬럼은 지우지 않고
더 이상 읽지 않는다. 운영 DB에
`SELECT count(*) FROM push_preferences WHERE types <> '{}'`가 0이면 이후 정리 마이그레이션으로 삭제할 수 있다.

검증 기준: 실제 PostgreSQL·Redis로 카테고리 조회·병합 저장·동시 PATCH·마스터 보존/복원·모두 꺼도 마스터 유지·모두 꺼진 상태에서
마스터를 껐다 켜면 같은 값·`types` 무시·세션 간 공유·미가입/미인증, 카테고리 분류표와 `allows` 규칙, 대기열 생성 시점과 전송
직전의 카테고리 적용, 14·15·16번의 마스터 의존과 카테고리 무관 발송, 카테고리 없는 종류 미발송, V35의 종류별 값 이관과 마스터·
16번 값 미이관을 확인한다.

## 저장·트랜잭션

V15는 다음 세 테이블을 추가한다. 기존 `notifications`를 포함해 네 테이블이며 별도 이벤트/수신자 원장·임시 큐·새 의존성은 없다.

| 테이블 | 주요 컬럼·키·삭제 관계 |
| --- | --- |
| `push_devices` | bigint identity PK; installation UUID UNIQUE; member FK CASCADE; sid UUID; environment/topic; AES-256-GCM token BYTEA; SHA-256 hash CHAR(64); authorization; registered_at |
| `push_preferences` | member PK/FK CASCADE; enabled; 카테고리 JSONB(V35, 없는 키는 켜짐); 예전 종류별 override JSONB(V15, 더 이상 읽지 않음); updated_at |
| `push_jobs` | UUID PK; device FK SET NULL; recipient member FK CASCADE; sid; bunch ID(의도적으로 FK 없음); notification FK SET NULL; dedupe/type/payload JSONB/status/attempts/available_at/expires_at/sent_at/last_error_code/created_at |

기기는 `(environment,topic,token_hash)` UNIQUE, `(member_id,session_id)` 인덱스를 가진다.
job은 `(recipient_member_id,device_id,dedupe_key)` UNIQUE와 `(status,available_at,id)` 인덱스를 가진다.
상태는 `PENDING/SENT/FAILED/CANCELLED`, attempts는 0~5다. 토큰 원문·JWT·인증 헤더는 로그/전송 기록에 남기지 않는다.
종료 job은 생성 후 30일 경과 시 정리한다. 회원 삭제는 FK로 기기·설정·job을 제거한다.

실제 업무 변경과 `notifications`·현재 수신 기기의 `push_jobs`를 같은 DB 트랜잭션에서 저장한다.
writer는 기존 업무 트랜잭션을 요구하며 실패하면 모두 롤백한다. 기기가 없거나 설정/OS 보고 권한이 꺼져 있으면 job을 만들지 않는다.
나중에 기기를 등록해도 과거 알림을 소급 전송하지 않는다. 업무 요청 replay는 job도 재생성하지 않는다.
10분 활동 합산은 기존 pending job 본문만 함께 갱신하며 최초 제목·job ID·발생 시각·만료는 유지한다.
이미 전송된 job이나 나중에 등록한 기기에는 추가 job을 만들지 않는다.
합산은 현재 `PENDING`인 job의 payload만 조건부 갱신한다. 동시 전송·취소가 기록한
상태·시도 횟수·재시도 시각을 이전 값으로 덮어쓰지 않는다.

내보내짐(14)·번치 삭제(15)는 inbox 없이 detached job만 저장한다. 번치 FK가 없으므로 삭제 뒤에도 안내를 전송할 수 있다.
이 producer 및 재참여 시 이전 removal job 취소는 [이탈 #25](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/25)·
[삭제 #50](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/50)의 실제 업무 트랜잭션에서 연결한다.
공동 관리자/인계(11~13)와 공개 전환(16)은 각 기능의 실제 notification 저장 지점에 연결했다.
현재 연결은 번들·리마인드·참여·역할·이탈·삭제·공개 전환·팔로우(1~7/9~19)다.
전체 21종 중 별도 생산자가 없는 아래 세 종류를 완료로 표시하지 않는다.
후보(8), 소셜 댓글·좋아요(20/21)는 별도 업무 생산자 연계가 남는다.

## 전송·취소

단일 worker가 15초마다 최대 50개 due job을 처리한다. 시도 횟수와 다음 시각을 DB에 먼저 커밋하고 HTTP는 트랜잭션 밖에서 호출한다.
전송 직전 계정·개인 설정·현재 기기 귀속·sid/Redis session·번치 소속·초안 참여/상태·차단을 다시 검사한다.
14/15는 현재 번치 소속을 요구하지 않는다. 무효 대상은 CANCELLED이며 업무 성공/inbox를 되돌리지 않는다.
코멘트 작성 완료·초안 참여 제외·발행·삭제 때 pending reminder를 업무와 함께 취소하고 worker도 현재 상태를 재검사한다.
프로세스 중단·응답 유실 시 재전송 가능성이 있으므로 정확히 한 번의 최종 전달을 보장하지 않는다.

JDK 21 HTTP/2와 기존 JWT 도구로 ES256 provider 인증을 사용한다. provider JWT는 50분 캐시하고 20분 이내 불필요하게 재발급하지 않는다.
요청 헤더는 `apns-topic`, `apns-push-type:alert`, `apns-priority:10`, job UUID인 `apns-id`,
발생 후 24시간인 `apns-expiration`이다. endpoint 연결 제한은 3초, 개별 요청 제한은 10초다.

| 응답·상태 | 처리 |
| --- | --- |
| APNs 200 | SENT; 접수 성공이며 실제 표시/읽음 증거는 아님 |
| 410 Unregistered / BadDeviceToken / DeviceTokenNotForTopic | 시도 당시 hash·등록 시각이 여전히 같은 기기만 삭제·pending 취소; 새 등록은 보존 |
| 429 / 5xx / 연결 실패·timeout | 15분 후 재시도; 더 긴 Retry-After 존중; 총 5회·발생 후 24시간 이내 |
| ExpiredProviderToken | 갱신 가능한 시점에 재서명/재시도; 기기 삭제 금지 |
| 그 외 provider 인증·topic·payload·4xx | FAILED; 같은 payload 무한 재시도 금지 |
| Redis 장애 | 15분 후 자격 재확인; 실제 APNs 미시도는 attempts를 소모하지 않음 |
| 만료·횟수 소진 | FAILED |

payload는 UTF-8 JSON 4096 bytes 이하, `aps.alert.title/body`, `sound:default`, `eventId/type/target`이다.
제목은 `[수신 번치 이름] 제목`, 이미 이름을 포함한 14/15는 중복 접두사를 붙이지 않는다.
소셜 댓글 설명은 줄바꿈을 공백으로 바꾸고 50 grapheme 뒤 `…`로 자른다. 총 byte 제한 초과 시 본문을 더 줄인다.
target은 내부 ID만 포함하며 실제 열기 시 현재 접근 권한을 재검사한다.

```json
{"aps":{"alert":{"title":"[퇴근런클럽] 새 멤버가 들어왔어요","body":"민수님이 번치에 참여했어요."},"sound":"default"},"eventId":"c340f5c9-1208-4692-a782-cebf4a76b316","type":"MEMBER_JOINED","target":{"kind":"MEMBERS","bunchId":42}}
```

## 환경·검증

`PUSH_DEVICE_ENCRYPTION_KEY`는 Base64 인코딩한 32-byte AES 키다. APNs는 `APNS_TEAM_ID`, `APNS_KEY_ID`,
`APNS_PRIVATE_KEY`(PKCS#8 PEM 또는 DER의 Base64), `APNS_SANDBOX_TOPIC`, `APNS_PRODUCTION_TOPIC`을 설정한다.
키 원문을 저장소에 넣지 않는다. provider 설정이 없으면 worker는 job을 소비하지 않는다.
기본 APNs endpoint는 Apple sandbox/production이며 loopback HTTP는 자동 테스트에서만 사용한다.
기존 `async-job.worker.enabled=false`로 전역 스케줄링을 끄면 테스트는 `runOnce`를 직접 호출한다.

실제 PostgreSQL·Redis로 migration/JPA validate·동시 설정·기기 재귀속·로그아웃·원자 롤백·자격 취소·재시도를 검증한다.
로컬 HTTP 서버로 JWT 서명·헤더·payload·provider 오류를 검증한다. 실제 APNs·iOS 권한·다중 기기 수신/복귀·WebView 검증은 별도 수동 단계다.

공식 근거: [기기 등록](https://developer.apple.com/documentation/usernotifications/registering-your-app-with-apns),
[provider 인증](https://developer.apple.com/documentation/usernotifications/establishing-a-token-based-connection-to-apns),
[요청](https://developer.apple.com/documentation/usernotifications/sending-notification-requests-to-apns),
[응답](https://developer.apple.com/documentation/usernotifications/handling-notification-responses-from-apns).
