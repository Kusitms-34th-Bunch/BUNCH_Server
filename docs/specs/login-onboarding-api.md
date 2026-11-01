# 로그인·온보딩 API 계약 — 이슈 #5

2026-10-17 사용자 합의. [인증](auth.md)·[온보딩](onboarding.md)의 미정 항목 중
아래 항목을 확정한다. 요구사항의 확정과 구현·실제 앱 연동 완료는 별개다.

## 범위와 최소 구현

AUTH-01~06, USER-01~02, 번치 생성·초대·참여(상세 2.1~2.7)의 서버 기능을 구현한다.
기존 auth/member/global을 확장하고 bunch에 번치·소속·초대를 둔다.
번치·소속·초대·참여 알림은 기존 테이블을 매핑한 JPA 엔티티와 JpaRepository로 관리한다.
소속의 번치·회원 참조는 단방향 LAZY이며, 기존 부분 유일 인덱스·JSONB 재시도 기록을 유지한다.
일반 조회·변경은 JPA로 처리하고 충돌 회피·JSONB 키 검색만 native query를 사용한다.
기존 OIDC 검증·재사용 방지·소셜 식별자 유일성은 유지한다.
이메일을 요청·추출·저장하지 않으며 ID 토큰에 포함되어도 계정 처리에 사용하지 않는다.
계정은 제공자와 고유 ID로만 식별한다. 회원·소셜 식별자의 기존 이메일 컬럼은 V4에서 삭제한다.
새 의존성, 서버 단계별 초안, 범용 온보딩/멱등성 프레임워크, 이벤트 버스는 추가하지 않는다.

별도 범위: 앱 UI·권한·브리지·기기 보관 구현, 공개 웹/AASA, Feed/번치 Home 본문,
소속 관리·회원 탈퇴 API, APNs·기기 등록, 약관 관리 화면·기존 회원 재동의.
가입 중 계정 자동 파기·로그인 횟수 제한은 기존 미정 상태를 유지한다.

## 가입·세션·약관

- SignupStatus: IN_PROGRESS / COMPLETED. 신규·기존 프로필 없는 회원은 가입 중이다.
- AccountStatus: ACTIVE / SUSPENDED / WITHDRAWN. 이용 불가 계정은 인증·가입·번치 기능을 거부한다.
  상태를 변경하는 관리·탈퇴 API는 이번 범위가 아니다.
- 계정 Role은 ROLE_USER 하나만 유지하고 enum으로 표현한다. Bunch의 OWNER와 별개다.
- 로그인마다 UUID 세션을 만든다. access/refresh에 같은 sid를 넣고 모든 인증 요청에서
  해당 Redis 세션을 확인한다. sid 없는 기존 토큰은 재로그인이 필요하다.
  Redis 확인 장애는 503 AUTH_SESSION_UNAVAILABLE로 응답하며 세션 만료 401과 구분한다.
- access 7일, refresh 14일. refresh 성공 시 14일로 갱신하고 비교·교체는 원자 수행한다.
  기존 access는 같은 세션이 유효한 동안 자신의 만료까지 유효하다.
- 로그아웃은 현재 세션만 삭제한다. 삭제된 세션은 재발급이 복원할 수 없다.
  재차 로그아웃은 이미 인증 불가하므로 401이다. 다른 기기 세션은 유지한다.
- 가입 중 허용: 로그인/재발급, 본인 프로필·온보딩 조회, 약관 조회·동의, 닉네임 확인, 가입 완료, 로그아웃.
  나머지 제품 기능은 403 SIGNUP_INCOMPLETE로 거부한다.
- 필수 약관 enum: SERVICE / PRIVACY / AGE_OVER_14. 선택 분석 동의는 DEMOGRAPHICS.
  앱이 승인된 원문을 제공하고 서버는 현재 버전과 동의 이력·시각을 관리한다.
  실제 버전은 설정값으로 공급하며 테스트용 v1을 운영 약관 버전으로 간주하지 않는다.
- 필수 항목 3개를 모두 현재 버전으로 제출해야 한다. 일부·중복 종류·미동의는 400,
  버전이 다르면 409. 동의 저장은 원자 처리하고 같은 버전 재요청은 이력을 중복 생성하지 않는다.
- 이미 동의한 가입 중 사용자는 일반 재진입 시 프로필로 간다. 프로필의 명시적 뒤로가기는
  동의를 유지한 약관 화면 표시를 허용하고 재저장을 요구하지 않는다.
- 별도 선택 동의는 분석 목적·회원 탈퇴 시까지 보관을 안내한다. 미동의 상태의 연도/성별
  제출은 400이며 미입력·미동의는 가입을 막지 않는다. 인구통계는 다른 사용자에게 비공개다.
- Gender: FEMALE / MALE, 미선택은 null. 연도는 1950..(현재 서울 연도-14) 범위다.
  가입 완료는 필수 동의와 최종 닉네임 검증 후 프로필·상태·시각을 함께 저장한다.
  동일 회원의 가입 완료 재요청은 같은 프로필이면 기존 결과를 반환하며 다른 입력은 409다.

## 문자·이미지

- 닉네임·번치 이름은 trim+NFC 후 1~12자, 한글·영문·숫자·ASCII 공백만 허용한다.
  NFC로 합쳐진 한글은 1자이며 남은 조합되지 않은 자모는 각각 1자(코드 포인트)로 센다.
  연속 자모를 하나의 grapheme로 합쳐 길이 제한을 우회하지 않는다.
  대소문자·내부 공백은 구분하며 조회·저장·요청 fingerprint에 같은 규칙을 쓴다.
  DB도 대소문자를 구분하는 유일성 제약을 둔다. 서버는 금지 문자를 임의 삭제하지 않는다.
- 소개는 앞뒤 공백·빈 줄 제거 후 1~50개 grapheme. 이모지·내부 줄바꿈을 허용한다.
- CharacterType: TYPE_1 / TYPE_2 / TYPE_3 / TYPE_4. 생성 후 변경하지 않는다.
  이미지·분위기 이름은 앱 매핑이며 초기 Stage는 1이다. Stage·발행 개수는 저장하지 않는다.
- 앱은 JPEG/PNG/HEIC/WebP를 선택받아 정사각형 JPEG로 변환한다. 서버 wire는 JPEG만 받는다.
  파일 최대 5MiB, 가로·세로 각각 최대 1024, 정사각형. decode 전에 크기를 확인하고
  JPEG로 재인코딩해 불필요한 EXIF 등 메타데이터를 제외한다.
- Cloudinary는 서버 RestClient의 HTTPS Basic Auth로 Upload API를 호출한다.
  API secret·Authorization·원문 토큰은 로그에 남기지 않는다. 사용자 입력 URL 원격 다운로드는 하지 않는다.
- 저장 정보는 공용 ImageAsset의 publicId/HTTPS URL이다. 개인·번치는 nullable
  `profile_image_asset_id`로 참조하며 기본 이미지는 imageUrl:null이다. 기존 데이터는
  재업로드 없이 백필하고 기존 응답 URL을 유지한다. [사진함 기반 계약](photo-foundation.md)을 따른다.
  회원 사진은 가입 multipart 요청에서 최종 저장한다. 사진 검증/저장만 실패하면 null로 가입을 완료한다.
  필수 프로필 오류·요청 전체의 파싱/전송 실패는 가입을 완료하지 않는다.
- 번치 사진은 OWNER만 즉시 변경·초기화 가능하다. 새 파일 업로드→DB 반영→커밋 후 미참조 기존 자산·파일 삭제 순서다.
  DB 실패 시 새 파일 삭제를 시도하며 기존 파일·참조는 유지한다.
  다른 프로필·Photo·발행본의 FK 참조가 있으면 자산·파일을 유지한다.
  외부 파일 삭제 실패는 publicId만 기록하고 수동 정리 대상으로 남긴다.

## 생성·초대·참여

- Visibility: PUBLIC / PRIVATE. 생성 시 명시적으로 선택한다.
- MembershipRole: OWNER / MEMBER, MembershipStatus: ACTIVE / LEFT / REMOVED.
  같은 회원·번치의 ACTIVE 소속은 하나다. 다시 참여하면 새 소속을 만든다.
- 번치 생성+첫 OWNER+마지막 사용 시각은 한 트랜잭션이다. 가입·권한 부여 시각을 남긴다.
- 소속 한도 10개, 번치 정원 8명. 변경 작업은 회원 행→번치 행 순으로 잠그고 최종 시점에 확인한다.
- 마지막 번치는 ACTIVE 소속의 lastUsedAt 내림차순으로 조회한다. 같으면 소속 ID 내림차순이다.
  무효 소속은 제외하므로 최근의 다른 유효 소속으로 복귀한다. 없으면 defaultBunchId:null이다.
- 초대 발급은 ACTIVE OWNER/MEMBER 모두 가능하다. 번치 잠금 아래 유효한 초대 하나를 재사용한다.
  만료/폐기면 기존 것을 폐기 표시하고 새로 발급한다. 최초 발급자 표시는 재공유해도 유지한다.
- 토큰은 SecureRandom 32바이트의 Base64URL, 보조 코드는 23456789ABCDEFGHJKLMNPQRSTUVWXYZ 중
  8자리다. 코드는 trim+대문자로 정규화한다. 충돌 시 재발급한다.
  링크와 코드는 같은 초대이며 7일 만료·폐기가 함께 적용된다. 보조 코드는 초대 화면/카드에 표시한다.
- Invitation 입력 하나에 전체 링크·토큰·코드를 허용한다. URL은 설정된 초대 기본 URL의
  origin/path만 허용하며 사용자가 준 URL을 서버에서 요청하지 않는다.
- 공개 카드는 번치 이름·소개·사진·보조 코드·유효한 발급자의 닉네임만 제공한다.
  정원·멤버 목록·내부 콘텐츠는 공개하지 않는다. 발급자가 비활성 계정/소속이면 From은 null이다.
- 인증 확인은 같은 카드와 InviteEligibility(AVAILABLE / ALREADY_MEMBER / FULL /
  LIMIT_EXCEEDED)를 반환한다. 수동·외부 링크 모두 10개 소속 상태에서도 카드 확인은 허용한다.
- 판단 순서: 초대 무효 → 이미 ACTIVE 소속 → 정원 8명 → 소속 10개.
  무효는 404로 통합하며 존재/만료/폐기를 구분하지 않는다.
- 참여는 가입 완료·초대·정원·소속 한도를 다시 확인하고 MEMBER 소속·최근 사용 시각·
  기존 멤버별 새 멤버 알림을 한 트랜잭션으로 저장한다. JoinOutcome: JOINED / ALREADY_MEMBER.
  이미 소속이면 200으로 반환하고 소속·알림을 중복 생성하지 않는다.
- 성공 참여 재시도는 초대 만료·폐기 이후에도 기존 성공 대상을 반환한다.
- 생성·참여 Idempotency-Key는 앱이 만든 UUID이며 회원+작업 단위로 해당 번치/소속에 보존한다.
  정규화 입력의 fingerprint를 비교하고 다른 입력은 409다. 참여 입력의 token/code/URL은
  같은 초대 ID로 비교한다. 성공 재생은 현재 유효성·정원 검사보다 먼저 한다.
  같은 결과의 ID는 유지하고 이름·사진은 현재 값을 반환한다. 새 참여는 새 키를 사용한다.
- 인증 확인/참여의 무효 입력은 회원별 10분 10회 실패까지 허용하고 이후 429.
  공개 조회는 원격 IP별 분당 60회. 설정으로 변경 가능하며 임의 X-Forwarded-For는 신뢰하지 않는다.
- 가입 완료와 자동 참여는 별도 요청/트랜잭션이다. 자동 참여 실패로 가입을 되돌리지 않는다.
  기기 초대는 최신 1개/24시간, 약관 뒤로가기 보존, 일반 로그아웃/참여 중단/완료/확정 실패 삭제,
  일시 장애 유지다. 자동 참여 확정 실패는 신규·기존 모두 시작 선택으로 이동한다.

## API

모든 응답은 기존 success/data/error envelope를 유지한다. 시간은 UTC ISO-8601이다.
인증 principal은 검증된 회원 ID이며, 공개로 명시하지 않은 API는 인증이 필요하다.

| 경로 | 요청 | data / 처리 |
| --- | --- | --- |
| POST /api/v1/auth/{apple,google,kakao}/login | 기존 idToken/nonce | 기존 accessToken/refreshToken, 독립 sid |
| POST /api/v1/auth/reissue | 기존 refreshToken | 같은 sid의 새 토큰 쌍 |
| POST /api/v1/auth/logout | access Bearer | null, 현재 세션 삭제 |
| GET /api/v1/members/me | 없음 | memberId, nickname, imageUrl, birthYear, gender, defaultBunch |
| GET /api/v1/members/me/onboarding | 없음 | memberId, signupStatus, termsAccepted, defaultBunchId |
| GET /api/v1/auth/terms | 없음 | terms: [{type,version,required}] |
| POST /api/v1/auth/terms/agreements | agreements:[{type,version,agreed:true}] | null |
| GET /api/v1/members/nickname-availability?nickname= | nickname | normalizedValue,available |
| POST /api/v1/members/me/registration | multipart profile(JSON):nickname,birthYear?,gender?,demographicsConsentVersion?; photo? | memberId,signupStatus,nickname,imageUrl |
| GET /api/v1/bunches/name-availability?name= | name | normalizedValue,available |
| POST /api/v1/bunches | JSON name,introduction,visibility,characterType + Idempotency-Key | id,name,characterType,imageUrl |
| PUT /api/v1/bunches/{id}/profile-image | multipart photo | id,name,characterType,imageUrl |
| DELETE /api/v1/bunches/{id}/profile-image | 없음 | 같은 번치 정보, imageUrl:null |
| GET /api/v1/bunches/{id}/members | 없음 | [{memberId,nickname,imageUrl,role}], 활성 멤버만([상세](#bunch-members)) |
| PUT /api/v1/bunches/{id}/last-used | 없음 | null |
| POST /api/v1/bunches/{id}/invitations | 없음 | url,code,expiresAt |
| GET /api/v1/invitations/{token} | 공개 | name,introduction,imageUrl,inviterNickname,code |
| POST /api/v1/invitations/verify | invitation | card,eligibility |
| POST /api/v1/invitations/join | invitation + Idempotency-Key | outcome,bunch(생성 응답과 동일) |

온보딩 상태 조회는 `GET /api/v1/members/me/onboarding`만 제공한다. 기존
`/api/v1/auth/session` 경로는 alias로 유지하지 않으며 인증된 요청도 404다.

<a id="member-profile"></a>
### 본인 프로필 조회 — 이슈 #11 (2026-10-18 추가)

`GET /api/v1/members/me`는 유효한 access JWT와 활성 Redis 세션으로 인증한 본인의
저장된 프로필만 조회한다. 회원 ID를 요청으로 받지 않으며 다른 회원의 프로필은 조회할 수 없다.
가입 중인 ACTIVE 계정도 200으로 조회할 수 있고 약관 동의·가입 완료를 요구하지 않는다.
온보딩 조회의 응답·도착지 판정 계약은 그대로 유지한다.
가입 상태는 `/me/onboarding`의 `signupStatus`로 판단하며 본인 프로필 응답에는 중복 포함하지 않는다.

| data 필드 | 타입·null 계약 |
| --- | --- |
| memberId | Long, 인증된 본인 ID |
| nickname | String 또는 null. 가입 완료 전 미저장 상태는 null |
| imageUrl | String 또는 null. 미등록·사진 저장 실패·기본 이미지이면 null이며 앱이 기본 이미지를 표시 |
| birthYear | Int 또는 null. 선택하지 않았으면 null, 본인에게만 반환 |
| gender | FEMALE / MALE 또는 null. 선택하지 않았으면 null, 본인에게만 반환 |
| defaultBunch | 기존 BunchResponse 형식의 {id,name,characterType,imageUrl} 또는 null |

`defaultBunch`는 가입 완료 회원의 ACTIVE 소속 중 lastUsedAt 내림차순, 같은 시각이면
소속 ID 내림차순으로 선택한다. 기존 온보딩의 defaultBunchId와 같은 기준이며 별도 기본 번치
설정을 저장하지 않는다. 가입 중이거나 유효한 소속이 없으면 null이다. 나가거나 내보내진 소속은
제외하고 번치가 삭제되면 다른 유효 소속으로 복귀한다. 번치 기본 이미지는 imageUrl:null이다.

null 필드도 JSON에 포함하며 임시 입력이나 기본값을 만들어 반환하지 않는다. 위 여섯 필드만
반환하고 사진 publicId·약관·소셜 식별자·내부 계정 정보는 포함하지 않는다.
미인증·잘못된/만료된 access JWT·유효하지 않은 세션은 기존 401 `AUTH_001`이다.
회원이 없거나 SUSPENDED/WITHDRAWN이면 온보딩 조회와 동일하게 현재 세션을 해제하고
401 `AUTH_001`로 응답한다. 실패 응답은 `success:false`, `data:null`인 기존 오류 형식이다.
Redis 세션 확인 장애는 기존 503 `AUTH_018`을 유지한다. 스키마·가입 저장 계약은 변경하지 않는다.

검증 기준: 실제 PostgreSQL·Redis와 JWT 인증을 거쳐 프로필과 기본 번치의 본인 조회 제한,
signupStatus 제외·가입 중/완료 상태의 null 필드·기본 번치 표시 정보·최근 사용/동률 순서·무효 소속/삭제 시 복귀,
미인증·잘못된 토큰·refresh 토큰·해제된 세션,
없는 회원·정지·탈퇴 계정의 401과 세션 해제를 확인하고 기존 온보딩 테스트를 유지한다.

<a id="bunch-members"></a>
### 번치 멤버 목록 조회 — 이슈 #43

`GET /api/v1/bunches/{id}/members`는 번치의 활성 멤버를 내려준다. 사진함·번들 만들기의 멤버별 필터 칩
(사진함 3.1.2, 번들 만들기 4.1.1), 번들 참여 멤버 추가(4.3.2), 멤버 관리(5.6.1)가 함께 쓴다.
가입을 완료한 그 번치의 ACTIVE 멤버만 조회할 수 있다.

| data 항목 | 타입·null 계약 |
| --- | --- |
| memberId | Long |
| nickname | String 또는 null. 가입 완료 멤버는 항상 있다 |
| imageUrl | String 또는 null. 미등록이면 null이며 앱이 기본 이미지를 표시 |
| role | `OWNER`(메인 관리자) / `MEMBER`. 공동 관리자 역할이 생기면 값이 추가된다 |

- 활성 멤버만 포함한다. 나가기·내보내짐으로 소속이 끝난 멤버와 탈퇴·정지 계정은 제외한다(3.1.2).
  사진이 없는 멤버도 포함하고, 나갔다가 다시 참여한 멤버는 한 번만 나온다.
- 정렬: 메인 관리자 먼저, 같은 역할 안에서는 닉네임 오름차순이다(5.6.1 로직 1). 닉네임은 DB 문자 코드
  순서(`COLLATE "C"`)라 한글끼리는 가나다순이고 영문·숫자가 한글보다 앞에 온다.
- 번치당 최대 8명이라 페이지가 없다. 출생연도·성별·계정 상태 등 다른 개인 정보는 내려주지 않는다(5.10.8 로직 2).
- 오류: 미인증 401 `AUTH_001`, 가입 미완료 403 `AUTH_015`, 번치 멤버가 아님 403 `AUTH_002`, 번치 없음 404
  `RESOURCE_001`.
- 멤버 관리 작업(권한 변경·내보내기)과 다른 번치의 멤버 목록(5.10.8, 팔로우 상태 확인 필요)은 이 API의 범위가 아니다.

검증 기준: 실제 PostgreSQL·JWT 인증을 거쳐 정렬(메인 관리자 우선, 닉네임순), 프로필 이미지 null,
나간·내보내진·탈퇴 멤버 제외, 재참여 1회 표시, 다른 번치 멤버 미포함, 비멤버·가입 미완료·없는 번치·토큰 없음을 확인한다.

오류는 ErrorCode enum을 확장한다: 입력400, 미인증/이용불가401, 가입미완료/권한403,
무효초대404, 이름중복/필수동의버전/이미가입완료다른입력/정원/소속한도/요청키충돌409,
요청제한429, 외부사진실패503. 서버 message key와 앱 안내 문구를 구분한다.

## 검증·배포 조건

실제 PostgreSQL/Redis에서 OIDC 회귀·세션 독립/회전/로그아웃 경합, 약관·가입 재개·닉네임
경합·사진 실패, 생성 롤백·동시 초대 발급·코드·정원/소속 한도·요청 재시도·알림 중복을 검증한다.
기존 Flyway는 수정하지 않으며 새 마이그레이션+JPA validate+전체 clean build가 필요하다.
사진 외부 호출은 로컬 HTTP stub으로 검증하고 실제 Cloudinary/iOS 연동은 별도로 기록한다.
실제 약관 원문/버전, Cloudinary 계정 설정, 초대 도메인·앱 이미지 매핑이 준비되어야 앱 연동 가능하다.

정리 계획: 인증의 단일 refresh 구현만 세션 단위로 교체하고 기존 검증기를 재사용한다.
기존 테스트로 OIDC·회원 유일성·응답 계약을 보호한 뒤 동작 변경 테스트를 교체/추가한다.
관련 없는 패키지 정리·의존성 변경은 하지 않는다.
