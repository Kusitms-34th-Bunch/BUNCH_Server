# 알림·푸시 API 계약

> #28 1차 상태 (2026-11-01): notifications 이관·가입 생산자 전환·목록/읽음/open과 알림 행의 활동 합산을 구현한다.
> 전체 21종 생산자·참여별 독촉 실행·기기/개인 설정/APNs 구현 완료를 뜻하지 않는다.
> 현재 연결 범위와 통합·출시 조건은 [1차 구현 경계](notification-foundation.md)를 따른다.
> 타 도메인의 `bunch*.md` 참조는 제공된 고정 승인 사본 기준이며 이 브랜치에 복제하지 않는다.

[알림 Spec](notification.md)과 [알림 스키마](notification-schema.md)의 목표 계약이다.
2026-10-31 번치 API에서 알림·푸시 소유 계약을 옮겼으며 경로·DTO·정책을 변경하지 않았다. 서버 구현 완료를 뜻하지 않는다.

## 1. 공통 계약·DTO

- 기본 경로 `/api/v1`. 활성 계정·가입 완료·활성 Redis 로그인 세션을 요구한다.
- `success/data/error`, UTC ISO-8601 시각, nullable 필드의 JSON null, 200 성공·데이터 없는 성공의 data:null을 유지한다.
  인증·공통 응답은 [기존 API 계약](login-onboarding-api.md#api)을 따른다.
- 목록은 page=0, size=20, size 1~50. 음수·범위 초과는 400이다. 페이지는 `{items,page,size,totalElements,hasNext}`다.
  동률은 시각 DESC,id DESC이며 앱은 ID로 중복 제거하고 재진입 때 첫 페이지를 갱신한다.
- principal로 본인 알림·기기와 현재 소속을 확인한다. 형식 오류는 COMMON_001(400), 타인/없는 알림·기기는 RESOURCE_001(404),
  세션·가입 완료 오류는 기존 AUTH 코드다. 번치 소속/접근 불가 판정은 번치 API (`bunch-api.md#목표-오류-코드`; 고정 승인 사본)가 소유한다.
- 아래 변경 API에는 새 Idempotency-Key나 설정 버전을 요구하지 않는다. 읽음·설정·기기 상태별 반복 규칙을 적용한다.

| DTO | 필드 |
| --- | --- |
| NotificationTarget | `kind,bunchId,bundleId?,noticeId?,targetBunchId?`; kind는 BUNCH_HOME / MEMBERS / ROLE_PERMISSIONS / DRAFT / DRAFT_LIST / BUNDLE / BUNDLE_COMMENTS / CANDIDATES / OTHER_BUNCH_PROFILE / ACCESS_NOTICE |

## 2. 알림 목록·이동·개인 설정

알림 종류별 제목·본문·받는 사람·이동은 [알림 Spec](notification.md)의 21종 표가 기준이다.
11·12·13번 역할 변경 알림의 target.kind는 ROLE_PERMISSIONS다. 별도 조회 API 없이 앱의 고정 권한 안내를 연다.
뒤로가기는 앱 내 알림/실행 중 푸시/번치 전환/신규 실행의 진입 경로에 따라 번치 Spec 5.6.2 (`bunch.md#bunch-5-6-2`; 고정 승인 사본)를 따른다.
여기서 type은 그 표의 번호 순서대로 아래 wire enum을 사용한다.

| 번호 | type | 번호 | type | 번호 | type |
| --- | --- | --- | --- | --- | --- |
| 1 | DRAFT_PARTICIPANT_ADDED | 8 | CANDIDATE_CREATED | 15 | BUNCH_DELETED |
| 2 | DRAFT_ACTIVITY | 9 | CHARACTER_GROWN | 16 | BUNCH_VISIBILITY_CHANGED |
| 3 | COMMENT_REMINDER | 10 | MEMBER_JOINED | 17 | BUNCH_FOLLOWED |
| 4 | DRAFT_PARTICIPANT_REMOVED | 11 | CO_OWNER_GRANTED | 18 | FOLLOW_REQUESTED |
| 5 | DRAFT_READY_TO_PUBLISH | 12 | CO_OWNER_REVOKED | 19 | FOLLOW_REQUEST_APPROVED |
| 6 | DRAFT_PARTICIPANTS_INSUFFICIENT | 13 | OWNER_TRANSFERRED | 20 | SOCIAL_COMMENT_CREATED |
| 7 | BUNDLE_PUBLISHED | 14 | MEMBER_REMOVED | 21 | BUNDLE_LIKED |

| 메서드·경로 | 입력 | data |
| --- | --- | --- |
| GET /bunches/{bunchId}/notifications | `page,size` | 아래 NotificationPage |
| PUT /bunches/{bunchId}/notifications/{notificationId}/read | 없음 | `{id,readAt}`; 반복 시 최초 readAt 유지 |
| POST /bunches/{bunchId}/notifications/read-all | 없음 | `{unreadCount}`; 해당 회원·번치의 보관 알림을 한 번에 읽음 처리 |
| POST /bunches/{bunchId}/notifications/{notificationId}/open | 없음 | `{notificationId,readAt,available,target}` |
| GET /members/me/push-preferences | 없음 | `{enabled,types}` |
| PATCH /members/me/push-preferences | `{enabled?,types?}` | `{enabled,types}`; 받은 항목만 변경 |

- NotificationPage는 공통 페이지에 `unreadCount`만 추가한다.
  items는 `{id,type,title,body,createdAt,readAt,target}`다. 본인·해당 번치만 createdAt DESC,id DESC로 제공한다.
  보관 30일 중 최신 100개까지 표시하므로 totalElements는 최대 100이다.
  unreadCount는 보관 중 전체 미확인 수이며 모두 읽음은 화면 밖의 과거 알림도 포함한다.
  별도 읽음 토큰이나 순번 없이 실행한 UPDATE가 읽음 상태를 바꾼다. 동시 활동 병합과는 DB 행 갱신 순서를 따른다.
  모두 읽음 뒤 저장된 새 알림·활동은 안 읽음이며 빈 목록에서는 성공 no-op이다.
- 화면 진입·앱 복귀·새로고침으로 갱신한다. 읽음은 회원별이며 다른 기기에서 다음 조회 시 보인다.
  홈·전환 목록과 unreadCount가 같은 필터를 사용한다. 30일 만료 알림은 정리 작업 전에도 조회·집계에서 제외한다.
- open은 먼저 해당 알림을 읽음 처리하고 현재 대상·권한을 검증한다. 대상이 없거나 볼 수 없으면
  `available:false,target:null`로 200을 반환하고 알림은 남긴다. 권한 없음과 삭제를 구분해 공개하지 않는다.
  실제 이동 실패에도 읽음 상태는 유지한다. 읽음 저장 자체가 실패하면 이동하지 않고 재시도한다.
- 내보내짐/번치 삭제에는 앱 내 Notification 행을 만들지 않는다. 푸시 target.kind=ACCESS_NOTICE와
  noticeId를 통해 본인의 안내를 조회/확인한다. 안내가 이미 만료되면 현재 유효한 defaultBunch로 복귀한다.
- 로그인 전에 푸시를 받으면 앱이 target을 기기에 보관하고 로그인 후 접근을 재검증한다.
  다른 번치로 이동하면 last-used를 갱신하고 전환 토스트는 표시하지 않는다.
- types는 위 21개 enum별 boolean이다. 미설정 회원/종류는 true다.
  PATCH는 받은 enabled·종류만 변경하고 빠진 종류는 유지한다. 알 수 없는 종류·boolean 아닌 값은 400이다.
  별도 설정 버전 없이 마지막 저장을 적용하며 같은 값 재전송은 no-op이다.
  설정은 개인 전체 기기의 푸시에만 적용하고 앱 내 알림은 유지한다. 14/15도 개인 설정을 따른다.

### 발생·병합·예약

- 업무 서비스가 실제 변경을 한 번 적용할 때 수신자 알림과 현재 기기별 전송 행을 함께 만든다.
  단순 재조회·성공 요청 재생·푸시 재시도는 알림을 새로 만들거나 합산하지 않는다.
  직접 행동의 본인은 제외하며 시스템 리마인드에는 미작성자 본인도 포함한다.
  일반 알림은 현재 소속·차단을 검사한다. 14/15의 이탈 대상 수신은 예외다.
- 제목/번치 이름은 발생 당시 값이다. DRAFT_ACTIVITY만 같은 수신자·초안의 첫 알림부터 10분간
  기존 알림 행에 행위자·사진 수를 합산하고 readAt을 비운다. 제목·createdAt·ID는 유지한다.
  앱 내 행은 즉시, 푸시는 그 행의 10분 창 종료 시 한 번 보낸다. 별도 이벤트/집계 원장은 만들지 않는다.
- COMMENT_REMINDER는 참여별 24h/72h 완료 여부를 참여 행에 기록하고 주기 조회한다. 최초 참여자는 생성 시점,
  추가 참여자는 추가 시점 기준이다. 발행·초안 삭제·참여 해제·코멘트 작성 시 남은 리마인드를 취소한다.
  발송 직전에 조건을 다시 확인한다. 늦게 실행되더라도 자격이 없으면 발송하지 않는다.
- DRAFT_READY_TO_PUBLISH는 한 초안의 최초 조건 충족 1회다. 번들에 최초 알림 여부를 남겨 조건이 깨졌다 다시 채워져도 재발송하지 않는다.
  CHARACTER_GROWN은 발행으로 Stage가 오른 경우에만 생성한다. 삭제로 하락한 뒤 다시 상승하면
  새 발행 사건으로 알릴 수 있다. 기준 설정 변경·기존 데이터 이관만으로 성장 알림을 만들지 않는다.
- BUNDLE_LIKED는 행동 번치 + 번들의 최초 좋아요 때 당시 수신자에게 1회다.
  좋아요 관계에 최초 알림 사실을 유지하고 취소/재등록도 다시 알리지 않는다.
  내부 번치의 좋아요/소셜 댓글, 차단 관련 사건, 사진 업로드/삭제, 초안 삭제, 발행본 수정/삭제,
  단순 프로필 변경·Stage 하락·팔로우 취소·거절·차단·신고 처리에는 알림을 만들지 않는다.
  공개 범위 변경은 단순 프로필 변경의 예외인 type 16으로 생성한다.

## 3. iOS APNs 기기·전송

| 메서드·경로 | 입력 | data |
| --- | --- | --- |
| PUT /members/me/push-devices/{installationId} | 아래 등록 body | `{installationId,environment,authorizationStatus,registeredAt}` |
| DELETE /members/me/push-devices/{installationId} | 없음 | `null`; 본인 바인딩만 해제, 이미 해제돼도 성공 |

등록 body는 `{deviceToken,environment,authorizationStatus}`다. installationId는 앱 설치별 UUID,
deviceToken은 네이티브 APNs Data를 소문자 hex로 인코딩한 값(짝수 길이, 1~2048 bytes)이다.
토큰을 32 bytes/64자리로 고정하지 않는다. environment는 SANDBOX / PRODUCTION,
authorizationStatus는 NOT_DETERMINED / DENIED / AUTHORIZED / PROVISIONAL이다.
앱 시작·토큰 콜백·로그인·권한 변경 때 등록하고 token을 서버 응답·로그에 다시 내보내지 않는다.

- 서버가 환경별 topic·endpoint를 설정한다. client가 APNs URL/topic을 지정할 수 없다.
  허용되지 않은 환경 등록은 400이다. 하나의 설치는 한 회원·현재 sid에 바인딩한다.
  같은 설치나 같은 환경/topic/token이 새 로그인으로 등록되면 이전 바인딩을 해제한다.
  worker는 job의 member·sid와 현재 기기 귀속·Redis 세션을 확인한다.
  로그아웃은 현재 sid의 바인딩을 해제하고, Redis에서 sid가 없어도 전달을 중단한다.
  다른 기기 세션은 유지한다. 계정 탈퇴는 전체 바인딩·미전송 작업을 취소한다.
- 같은 설치를 다른 회원이 이미 바인딩한 경우 DELETE는 그 바인딩을 지우지 않고 404를 반환한다.
  재시도는 항상 본인 확인을 다시 한다. 새 등록은 설치 UUID만으로 인증하지 않고 현재 세션으로 귀속한다.
- token은 서버 비밀 키로 암호화해 보관하고 비교용 hash를 사용한다. 해제/무효 토큰은 즉시 지우며
  전송 기록에는 token 대신 내부 device ID와 APNs reason만 남긴다.
- 푸시 payload는 `aps.alert.title/body`, `sound:"default"`, 별도 `eventId,type,target`를 사용한다. eventId는 기기별 전송 job ID다.
  target에는 번치/번들/notice ID만 넣고 세션·토큰·임의 외부 URL을 넣지 않는다.
  title은 `[수신 번치 이름] 제목`, 단 14/15는 이미 이름이 들어가므로 접두사를 추가하지 않는다.
  소셜 댓글 설명은 줄바꿈을 공백으로 바꾸고 최대 50 grapheme 뒤 `…`로 자른다.
  전체 UTF-8 JSON payload는 4096 bytes 이하여야 하며 초과 시 alert.body를 grapheme 단위로 더 줄인다.
- JDK 21 HTTP/2와 기존 JWT/암호화 도구로 APNs token 인증(ES256)을 사용한다. 새 SDK 의존성을 추가하지 않는다.
  provider JWT는 50분마다 교체하고 20분 이내 불필요한 교체를 하지 않는다.
  `apns-topic`, `apns-push-type:alert`, `apns-priority:10`, 전송 ID UUID인 `apns-id`를 쓴다.
  종료 시각은 발생 후 24시간이며 만료는 FAILED로 끝내고 더 보내지 않는다.
- APNs 200은 접수 성공이다. 사용자의 수신·표시·읽음 증거로 보지 않는다.
  업무 재시도의 중복 생성은 막는다. 단일 worker의 프로세스 중단·네트워크 응답 유실에 따른 재전송 가능성은 남는다.

| 결과 | 처리 |
| --- | --- |
| 200 | SENT 기록, 다시 보내지 않음 |
| 410 Unregistered | 시도 당시 token hash·등록 시각과 일치하는 기기만 무효화; 새 등록은 유지 |
| BadDeviceToken / DeviceTokenNotForTopic | 해당 환경/topic/시도 토큰이 여전히 현재 값일 때만 중단; 잘못된 운영 설정 여부도 점검 |
| ExpiredProviderToken | provider token을 갱신할 수 있는 시점에 재서명 후 재시도, 기기 토큰 삭제 금지 |
| 그 외 인증·topic·payload·4xx 오류 | FAILED, 같은 payload 무한 재시도 금지; 운영 오류 집계 |
| 429 / 5xx / 연결 실패·timeout | 15분 뒤 재시도, 최대 5회·24시간. Retry-After가 더 길면 존중 |
| 개인 설정/권한/세션/소속/차단 자격 상실 | CANCELLED, 앱 내 알림과 업무 결과 유지 |

OS 권한은 iOS 보고 값이며 서버가 팝업을 띄우거나 실제 배달을 보장하지 않는다.
등록 시 권한은 선택 정보이고 DENIED/NOT_DETERMINED에는 alert를 전송하지 않는다.
Redis/APNs 장애 시 앱 내 알림을 삭제하지 않고, 만료·최대 횟수 안에서 전송만 재시도한다.

공식 근거: [기기 등록](https://developer.apple.com/documentation/usernotifications/registering-your-app-with-apns),
[provider 인증](https://developer.apple.com/documentation/usernotifications/establishing-a-token-based-connection-to-apns),
[요청·payload](https://developer.apple.com/documentation/usernotifications/sending-notification-requests-to-apns),
[개별 기기 응답 코드](https://developer.apple.com/library/archive/documentation/NetworkingInternet/Conceptual/RemoteNotificationsPG/CommunicatingwithAPNs.html).
일반 alert 전송에 broadcast channel API를 혼용하지 않는다.

## 4. 요청·응답 예시

이미 삭제된 번들 알림을 open한 응답이다. 알림 읽음은 성공하고 이동만 불가하다.

```json
{"success":true,"data":{"notificationId":801,"readAt":"2026-10-29T09:10:00Z","available":false,"target":null},"error":null}
```

APNs alert payload 예시다. 실제 device token·provider JWT는 예시나 서버 로그에 넣지 않는다.

```json
{
  "aps":{"alert":{"title":"[퇴근런클럽] 새로운 번들 후보가 생겼어요","body":"함께한 순간들을 확인하고 새로운 번들로 만들어 보세요."},"sound":"default"},
  "eventId":"c340f5c9-1208-4692-a782-cebf4a76b316",
  "type":"CANDIDATE_CREATED",
  "target":{"kind":"CANDIDATES","bunchId":42}
}
```

## 5. 검증·앱 연동

- 본인/현재 번치 접근, 읽음과 새 활동 경합, 30일/100개·미확인 수, 생산자 중복·리마인드 취소를 검증한다.
- 업무·알림·기기별 전송 대기의 원자성, 단일 worker 재시작·만료/횟수, 오래된 APNs 실패의 새 토큰 격리를 검증한다.
- 실제 iOS의 권한·다중 기기·로그아웃·비로그인 복귀·번치 전환·삭제 대상 안내와 SANDBOX/PRODUCTION 전달은 별도 수동 검증이다.
  문서 예시 파싱·모의 APNs의 자동 테스트만으로 실제 기기 전달을 완료했다고 표시하지 않는다.
