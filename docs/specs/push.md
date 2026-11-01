# 개인 푸시·APNs 계약 — #48

[알림 Spec](notification.md)·[inbox API](notification-api.md)·[저장 계약](notification-schema.md)의 기기별 전송 계약이다.
기능·테스트·V15 migration과 함께 관리한다. 앱 내 알림은 개인 푸시 설정과 독립적이다.

## API

기본 경로 `/api/v1`, 활성 로그인 session·가입 완료 계정, `success/data/error`, UTC ISO-8601을 사용한다.

| 메서드·경로 | 요청 | 성공 data |
| --- | --- | --- |
| `PUT /members/me/push-devices/{installationId}` | `{deviceToken,environment,authorizationStatus}` | `{installationId,environment,authorizationStatus,registeredAt}` |
| `DELETE /members/me/push-devices/{installationId}` | 없음 | `null` |
| `GET /members/me/push-preferences` | 없음 | `{enabled,types}` |
| `PATCH /members/me/push-preferences` | `{enabled?,types?}` | `{enabled,types}` |

`installationId`는 설치별 UUID다. 토큰은 APNs Data를 hex로 인코딩한 짝수 길이 2~4096자(1~2048 bytes)이며,
32 bytes로 고정하지 않는다. 대문자 입력도 소문자로 정규화한다. 토큰은 응답에 포함하지 않는다.
환경은 `SANDBOX`/`PRODUCTION`, 권한은 `NOT_DETERMINED`/`DENIED`/`AUTHORIZED`/`PROVISIONAL`이다.
topic과 endpoint는 서버 설정을 사용하며 앱이 지정하지 않는다. 해당 환경의 topic이 없으면 400이다.

- 앱 시작·로그인·토큰 콜백·권한 변경 때 같은 설치에 PUT한다. 같은 귀속·토큰·권한이면 no-op이며 기존 pending job을 보존한다.
- 다른 회원/sid 또는 새 설치로 같은 환경/topic/token을 등록하면 이전 귀속의 미전송 job을 취소하고 현재 바인딩으로 교체한다.
- DELETE는 본인·현재 sid 소유만 해제한다. 이미 없으면 성공, 다른 귀속이면 404 `RESOURCE_001`이다.
- 로그아웃은 현재 sid의 기기와 pending job만 해제·취소한다. 다른 sid는 유지한다.
  등록·해제·로그아웃은 회원 잠금 아래 활성 session을 다시 확인한다.
- 설정 기본값은 전체·21종 모두 `true`다. PATCH는 받은 항목만 변경한다. 서로 다른 종류의 동시 PATCH는 둘 다 보존한다.
  미지의 종류는 400 `COMMON_001`, 문자열·숫자·null 종류 값은 400 `COMMON_002`다. JSON boolean만 받는다.
  같은 PATCH 재시도는 같은 결과이며 별도 요청 키·버전은 요구하지 않는다.
- 형식·enum·토큰 오류는 400, 비활성 session은 401, 미가입 완료는 기존 가입 오류다.
  `enabled`와 종류별 설정은 inbox 생성·읽음에 영향을 주지 않는다. 알림 읽음만으로 pending push를 취소하지 않는다.

```json
{"deviceToken":"a1b2c3d4","environment":"SANDBOX","authorizationStatus":"AUTHORIZED"}
```

```json
{"enabled":true,"types":{"COMMENT_REMINDER":false,"BUNDLE_PUBLISHED":true}}
```

## 저장·트랜잭션

V15는 다음 세 테이블을 추가한다. 기존 `notifications`를 포함해 네 테이블이며 별도 이벤트/수신자 원장·임시 큐·새 의존성은 없다.

| 테이블 | 주요 컬럼·키·삭제 관계 |
| --- | --- |
| `push_devices` | bigint identity PK; installation UUID UNIQUE; member FK CASCADE; sid UUID; environment/topic; AES-256-GCM token BYTEA; SHA-256 hash CHAR(64); authorization; registered_at |
| `push_preferences` | member PK/FK CASCADE; enabled; 종류별 override JSONB; updated_at |
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

내보내짐(14)·번치 삭제(15)는 inbox 없이 detached job만 저장한다. 번치 FK가 없으므로 삭제 뒤에도 안내를 전송할 수 있다.
이 producer 및 재참여 시 이전 removal job 취소는 [이탈 #25](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/25)·
[삭제 #50](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/50)의 실제 업무 트랜잭션에서 연결한다.
공동 관리자/인계(11~13)와 공개 전환(16)도 각 기능의 실제 notification 저장 지점에서 연결해야 한다.
#48은 이를 위한 writer만 제공했다고 전체 producer 완료로 표시하지 않는다.
현재 연결은 번들·리마인드·참여·팔로우(1~7/9/10/17~19)다.
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
{"aps":{"alert":{"title":"[퇴근런클럽] 새로운 멤버가 참여했어요","body":"새 멤버와 함께 추억을 쌓아 보세요."},"sound":"default"},"eventId":"c340f5c9-1208-4692-a782-cebf4a76b316","type":"MEMBER_JOINED","target":{"kind":"MEMBERS","bunchId":42}}
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
