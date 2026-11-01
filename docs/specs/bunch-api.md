# 번치 API 계약

2026-10-29 승인한 **목표 계약**이다. [번치 제품 Spec](bunch.md)과
[DB·트랜잭션 계약](bunch-schema.md)을 함께 적용한다. 이 문서를 추가해도 서버에 API가
등록되는 것은 아니다. 최초 작성 기준은 dev `159937b6`와 당시 PR #32 `6c70f09`다.
#32는 2026-10-29 병합됐으며, 2026-10-31 최신 시트·사용자 답변을 재대조했다.
여기에 명시한 변경은 최신 dev에서 별도 기능 PR로 DTO·테스트·마이그레이션과 함께 구현한다.
YAGNI 정리에 따라 홈·멤버 조회를 재사용하고, 상태 변경은 필요한 요청 키·기존 잠금·DB 제약만 사용한다.

## 1. 공통 계약

- 기본 경로 `/api/v1`. 모든 API는 활성 계정·가입 완료·활성 Redis 로그인 세션을 요구한다.
  공개 초대장 API의 비인증 계약은 유지하지만, 아래의 다른 번치 조회는 **인증된 앱 사용자** 대상이다.
- 시간은 UTC ISO-8601, 날짜는 `yyyy-MM-dd`, ID는 양의 정수다. nullable 필드는 JSON `null`로 보낸다.
- 정상은 `200 OK`, 데이터 없는 성공은 `data:null`이다. 기존 응답 envelope와 오류 message key를 유지한다.
- 본문의 회원 ID·역할을 인증으로 신뢰하지 않는다. principal과 활성 Membership을 매번 확인한다.
  권한 DTO는 화면 표시용이며 서버 검증을 대체하지 않는다.
- 페이지 목록은 `page=0`, `size=20`, `size` 범위 1~50이다. 음수·범위 초과는 400이다.
  공통 페이지는 `{items,page,size,totalElements,hasNext}`다. 정렬은 각 절의 시각 DESC, ID DESC를
  함께 적용한다. 편집·삭제로 페이지가 달라질 수 있어 앱은 ID로 중복을 제거하고 재진입 시 첫 페이지를 갱신한다.
- `Idempotency-Key: UUID`는 프로필 저장·메인 인계·나가기·번치 삭제에만 필수다.
  기존 생성·참여·번들 API의 키 요구는 유지한다. 역할/관계 PUT·DELETE·내보내기는
  현재 상태·대상 ID·버전·DB 유일성으로 중복 효과를 막는다. 앱은 상반된 관계 요청을 순서대로 보낸다.
- `actorMembershipId`는 본인의 활성 참여 회차다. 인계·역할 변경·이탈·삭제에는
  멤버 목록의 `expectedMembershipVersion`, 프로필에는 `expectedProfileVersion`을 사용한다.
  버전은 해당 데이터가 실제로 바뀔 때만 증가한다. 최근 사용/조회는 증가시키지 않는다.
- 위 4개 요청 키는 회원+작업+번치별로 성공 후 30일 보존한다. 정규화 입력·기대 버전·
  교체 이미지 bytes의 SHA-256을 비교하고 같은 키·다른 입력은 REQUEST_001이다.
  성공 기록에는 최소 결과만 저장한다. 이탈/인계/삭제 뒤 같은 성공 요청은 현재 세션을 확인하고
  최소 결과를 재생한다. 새 키는 현재 권한으로 처리하며 과거 Membership을 새 소속에 적용하지 않는다.
- 이름은 trim+NFC, 1~12 코드 포인트, 기존 허용 문자·전체 유일성 규칙을 쓴다.
  소개는 앞뒤 공백·빈 줄 제거 후 1~50 grapheme, 내부 줄바꿈·이모지는 보존한다.

```json
{"success":true,"data":{"id":42},"error":null}
```

```json
{"success":false,"data":null,"error":{"code":"BUNCH_005","message":"error.bunch_membership_version_conflict","detail":{"membershipVersion":12}}}
```

### 목표 오류 코드

기존 COMMON/AUTH/RESOURCE/REQUEST/IMAGE 코드는 재사용한다. 아래 신규 코드는 구현 시
`ErrorCode`에 추가할 예약 계약이며 현재 등록되어 있다는 뜻이 아니다.

| HTTP | code / enum | 의미·앱 처리 |
| --- | --- | --- |
| 400 | COMMON_001 / INVALID_INPUT | 형식·문구·길이·페이지·불가능한 이미지 조합 오류 |
| 401 | 기존 AUTH 코드 | 로그인·세션 만료, 앱의 인증 복구 |
| 403 | AUTH_015 / SIGNUP_INCOMPLETE | 가입 완료 후 재시도 |
| 403 | AUTH_002 / FORBIDDEN | 현재 역할·대상 소속으로 허용하지 않는 작업 |
| 404 | RESOURCE_001 / RESOURCE_NOT_FOUND | 없는 리소스, 다른 사람의 안내·알림·기기, 다른 번치의 하위 데이터 |
| 409 | BUNCH_001 / BUNCH_NAME_TAKEN | 저장 시 이름 선점; 입력 유지 |
| 409 | BUNCH_004 / BUNCH_PROFILE_VERSION_CONFLICT | 현재 프로필과 버전을 재조회해 사용자 변경을 다시 확인 |
| 409 | BUNCH_005 / BUNCH_MEMBERSHIP_VERSION_CONFLICT | 멤버·역할이 바뀜; 작업하지 않고 목록 갱신 |
| 409 | BUNCH_006 / BUNCH_OWNER_SUCCESSOR_REQUIRED | 메인 관리자 나가기에 후임 필요 |
| 409 | BUNCH_007 / BUNCH_SOLE_MEMBER_CANNOT_LEAVE | 혼자인 번치, 삭제 안내 |
| 409 | BUNCH_008 / BUNCH_ACCESS_UNAVAILABLE | 본인의 미확인 접근 불가 안내가 있음; detail은 noticeId와 reason만 |
| 404 | BUNCH_009 / BUNCH_PROFILE_UNAVAILABLE | 외부 조회 대상 없음·차단·운영 비노출을 동일하게 처리, 원인/방향 비공개 |
| 409 | BUNCH_010 / CURRENT_BUNCH_REQUIRED | 소속 0개 사용자의 번치 명의 행동, 생성·참여 안내 |
| 409 | REQUEST_001 / IDEMPOTENCY_CONFLICT | 같은 키·다른 입력 |
| 503 | IMAGE_001 / PROFILE_IMAGE_FAILED | 이미지 저장 실패, 전체 프로필 저장 실패·입력 유지 |
| 503 | AUTH_018 / AUTH_SESSION_UNAVAILABLE | Redis 장애, 만료로 오인해 세션을 지우지 않음 |

현재 소속이 없을 때 내부 API는 본인의 유효한 안내가 있으면 BUNCH_008, 없으면 기존 403을 쓴다.
이 검사는 대상 정보 조회보다 앞서며 타인의 과거 소속·삭제 사실을 공개하지 않는다.
외부 API는 내부 소속 권한으로 우회하지 않고 선택한 viewer 번치 기준으로 판단한다.

## 2. 공통 DTO

| DTO | 필드 |
| --- | --- |
| BunchCard | `id,name,imageUrl,characterType,visibility` |
| MyMembership | `membershipId,role,joinedAt` (`role: OWNER / CO_OWNER / MEMBER`) |
| BunchPermissions | boolean `canEditProfile,canDeleteBunch,canManageRoles,canTransferOwner,canBlock,canInvite,canLeave` |
| MemberItem | `membershipId,memberId,nickname,imageUrl,role,isMe,joinedAt,actions` |
| MemberItem.actions | boolean `canGrantCoOwner,canRevokeCoOwner,canTransferOwner,canRemove`; 본인에게는 모두 false |
| MembersResponse | `bunchId,membershipVersion,myMembership,members:MemberItem[]` |
| MembershipMutationResult | `bunchId,membershipVersion,changedMembershipIds,ownerMembershipId`; 완료 시 ID·버전만 반환, 이후 목록은 현재 권한으로 다시 조회 |
| AccessNotice | `id,bunchId,name,characterType,reason,occurredAt,expiresAt`; `reason: REMOVED / DELETED` |
| DepartureResult | `bunchId,departedMembershipId,outcome,defaultBunchId`; `outcome: LEFT / REMOVED / DELETED` |
| FollowState | `NONE / REQUESTED / FOLLOWING`, viewer에서 대상으로의 상태 |
| RelationItem | `bunch:BunchCard,followState,followId?,followedAt` |

MemberItem의 개인정보는 닉네임·이미지·역할·가입일과 운영에 필요한 ID로 제한한다.
외부 멤버 조회에서는 아래의 별도 PublicMember를 사용해 가입일과 관리 action을 제거한다.

## 3. 메인·전환·프로필

| 메서드·경로 | 입력 | data / 동작 |
| --- | --- | --- |
| GET /members/me/bunches | 선택 `currentBunchId` | `{activeCount,maxActiveCount:10,defaultBunchId,activeBunches,unavailableBunches}` |
| GET /bunches/{bunchId}/home | 없음 | 아래 HomeResponse |
| PUT /bunches/{bunchId}/last-used | 기존 계약 | `null`; 대상 활성 소속 확인 후 lastUsedAt 갱신 |
| GET /members/me/bunch-access-notices/{noticeId} | 없음 | AccessNotice; 푸시 진입용, 본인의 미확인·미만료 안내만 |
| DELETE /members/me/bunch-access-notices/{noticeId} | 없음 | `null`; 본인의 안내만 제거, 이미 지운 ID도 200 |
| GET /bunches/name-availability | `name`, 선택 `excludeBunchId` | 기존 `{normalizedValue,available}`; 제외 번치의 메인 관리자 여부 검증 |
| PUT /bunches/{bunchId}/profile | 아래 multipart | `{bunchId,profileVersion}`; 전체 일괄 저장, 앱 입력을 새 기준으로 삼고 홈 재조회 |

- activeBunches 원소는 `{bunch:BunchCard,myMembership,unreadCount,lastUsedAt}`다.
  현재 번치가 활성 소속이면 맨 위, 나머지는 lastUsedAt DESC, membershipId DESC로 정렬한다.
  currentBunchId는 표시 순서에만 사용하고 last-used를 변경하지 않는다.
- unavailableBunches는 미확인·미만료 AccessNotice를 occurredAt DESC, id DESC로 반환한다.
  접근 불가 목록은 활성 10개 한도와 별개다. 이미지 원본은 남기지 않고 characterType 기본 이미지로 표시한다.
  동일 번치에 새 활성 소속이 있으면 이전 안내를 숨긴다. 본인이 직접 나가거나 삭제한 경우 안내를 만들지 않는다.
- 소속 0개는 activeBunches=[], defaultBunchId=null인 정상 응답이다. home은 유효한 번치 ID로만 요청한다.
- HomeResponse는 `{bunch,myMembership,permissions,profileVersion,membershipVersion,memberCount,
  followerCount,followingCount,draftCount,publishedCount,stage,unreadCount}`다.
  bunch는 BunchCard에 `introduction`을 추가한다. 공개·비공개 번들 모두 publishedCount·Stage에 포함한다.
  팔로우 수는 승인 관계만, 차단 관계를 제외한다. 실제 데이터 기반으로 집계하며 미구현 기능을 가짜 0으로 응답하지 않는다.
- 홈과 /bundles/summary는 동일 Stage 계산과 통계를 재사용한다. 홈은 번들 전체 snapshot을 포함하지 않는다.
  한 응답의 count·Stage는 같은 DB read snapshot에서 계산한다. 홈과 별도 목록 사이의 동시 변경은 새로고침으로 복구한다.
- 가입/앱 실행의 defaultBunch 복구는 기존 온보딩 계약대로 무효 소속을 건너뛴다.
  이미 앱에서 보고 있던 번치의 접근권한 상실만 안내 모달로 처리한다.

홈 응답 예시(번치 ID·이름은 가상 데이터):

```json
{
  "success": true,
  "data": {
    "bunch": {"id":42,"name":"퇴근런클럽","imageUrl":null,"characterType":"TYPE_1","visibility":"PRIVATE","introduction":"함께 달린 기록"},
    "myMembership": {"membershipId":101,"role":"CO_OWNER","joinedAt":"2026-10-20T09:00:00Z"},
    "permissions": {"canEditProfile":false,"canDeleteBunch":false,"canManageRoles":false,"canTransferOwner":false,"canBlock":true,"canInvite":true,"canLeave":true},
    "profileVersion":3,"membershipVersion":12,"memberCount":4,
    "followerCount":2,"followingCount":1,"draftCount":1,"publishedCount":6,"stage":3,"unreadCount":2
  },
  "error": null
}
```

### 프로필 multipart

`Content-Type: multipart/form-data`. `profile` part는 application/json, `photo`는 선택 파일이다.

```json
{"name":"퇴근런클럽","introduction":"함께 달린 기록","visibility":"PRIVATE","imageAction":"KEEP","expectedProfileVersion":3}
```

| imageAction | photo | 의미 |
| --- | --- | --- |
| KEEP | 없어야 함 | 현재 이미지 유지 |
| REPLACE | 필수 | 새 이미지로 교체 |
| RESET | 없어야 함 | 기본 이미지, asset 참조 null |

- 원본 선택: JPEG/PNG/HEIC/WebP, 10MB 이하(10,000,000 bytes). 앱에서 1:1 편집 후 JPEG로 변환한다.
  wire는 기존 서버 계약의 JPEG·최대 5MiB·정사각형·가로세로 최대 1024다. EXIF를 제거하고 서버도 재인코딩한다.
  원본 허용 형식이 서버의 HEIC 디코더 추가를 요구하지는 않는다.
- 필수 필드·이름 유일성·역할·버전·이미지 검증 중 하나라도 실패하면 이름·소개·공개 범위·이미지를 모두 유지한다.
  가입 시 이미지 실패에도 가입을 완료하는 예외를 이 API에 적용하지 않는다.
- 외부 업로드 전 사전 검증, 업로드 후 번치 잠금 아래 권한·버전·요청 키 재검증을 수행한다.
  중복 업로드가 발생해도 DB 반영은 한 번이다. 버려진 새 파일은 참조 해제·정리 대상으로 기록한다.
  업로드 동안 DB 행 잠금을 유지하지 않는다. 기존 이미지는 DB 커밋 후 참조가 없을 때만 정리한다.
- 모든 값이 정규화 후 같으면 성공하지만 profileVersion을 증가시키거나 알림을 생성하지 않는다.
  같은 이름은 사용 가능하다. 공개 범위 변경은 별도 모달 API 없이 저장하며, 비공개→공개는
  대기 요청 전체 승인과 원자 처리한다. 개별 번들의 visibility 값은 변경하지 않는다.
- 기존 생성 완료 화면의 PUT/DELETE /profile-image는 즉시 저장 계약을 유지한다.
  신규 역할 적용 후에는 이 경로도 OWNER만 가능하며 profileVersion을 증가시킨다.

## 4. #32 번들 API 재사용과 변경

| API | 목표 계약의 변경 |
| --- | --- |
| GET /bunches/{bunchId}/bundles?status=DRAFT | 생성 시각 대신 `updatedAt DESC,id DESC`; 현재 최대 10개. 기존 snapshot 페이지 형태 유지 |
| GET /bunches/{bunchId}/bundles?status=PUBLISHED | 기존 publishedAt DESC,id DESC; 소속 멤버는 PUBLIC/PRIVATE 모두 조회 |
| GET /bunches/{bunchId}/bundles/summary | `{draftCount,publishedCount,stage}` 유지, Stage 기준 0/2/6/10로 변경 |
| DELETE /bunches/{bunchId}/bundles/{id} | DRAFT는 생성자 또는 참여 중인 OWNER/CO_OWNER; PUBLISHED는 OWNER만 |
| 발행본 제목·사진·날짜·위치·캡션·공개 범위 저장 | OWNER만 공용 내용 변경; 기존 내용 버전·발행 조건 유지 |
| 초안 편집·발행 | 현재 참여자·생성자 규칙 유지, 참여 관리자에는 OWNER와 CO_OWNER 모두 포함 |
| 본인 코멘트 | 작성자만 수정, 발행 후 빈 값/삭제 불가 유지 |

새 번들 목록 API를 중복 생성하지 않는다. #32의 BundlePermissions를 상태별 새 권한과 맞추며,
STOMP 송수신·lease 검증도 변경된 역할/소속을 검사한다. 부모 번치 삭제는 DB cascade만으로
완료하지 않고 연결과 lease를 정리한다. #32의 현재 계약과 실제 코드가 위 목표에 맞는지는 후속 구현에서 검증한다.

## 5. 멤버·인계·이탈·번치 삭제

| 메서드·경로 | 입력 | data |
| --- | --- | --- |
| GET /bunches/{bunchId}/memberships | 없음 | MembersResponse; OWNER→CO_OWNER→MEMBER, 각 역할 닉네임 C collation ASC, membershipId ASC |
| PUT /bunches/{bunchId}/memberships/{membershipId}/role | `{actorMembershipId,expectedMembershipVersion,role}` | MembershipMutationResult; role은 CO_OWNER/MEMBER만 |
| POST /bunches/{bunchId}/owner-transfer | `{actorMembershipId,expectedMembershipVersion,targetMembershipId}` | MembershipMutationResult; 대상 OWNER, 요청자 MEMBER |
| POST /bunches/{bunchId}/leave | `{actorMembershipId,expectedMembershipVersion,successorMembershipId?}` | DepartureResult |
| POST /bunches/{bunchId}/memberships/{membershipId}/remove | `{actorMembershipId,expectedMembershipVersion}` | `{bunchId,removedMembershipId,membershipVersion,memberCount}` |
| POST /bunches/{bunchId}/deletion | `{actorMembershipId,expectedMembershipVersion,confirmation}` | DepartureResult; departedMembershipId는 요청자의 소속 |

- 멤버 목록은 최대 8개로 페이지를 나누지 않는다. 한글·영문·숫자를 허용하는 기존 닉네임의
  정렬을 DB C collation으로 고정한다. 이는 언어별 사전식 정렬을 약속하는 것은 아니다.
- 역할 변경·인계는 OWNER만 가능하다. 역할 API로 OWNER를 만들거나 해제할 수 없다.
  자기 자신 변경·내보내기·본인에게 인계는 400이다. CO_OWNER는 MEMBER만 내보낼 수 있다.
  대상 OWNER 내보내기는 어떤 역할도 허용하지 않는다. 대상 소속이 다른 번치면 404다.
- 별도 프로필/삭제/나가기 미리보기 API를 만들지 않는다. home과 memberships 응답으로
  현재 값·역할·후임 후보·확인 화면을 표시한다. 실행 요청이 최신 권한·버전을 다시 검사한다.
- OWNER의 나가기는 같은 번치의 다른 활성 successorMembershipId가 필수다.
  MEMBER/CO_OWNER가 successor를 보내면 400이다. 혼자인 OWNER는 삭제로 안내한다.
- 같은 목표 역할의 PUT과 이미 REMOVED인 대상 Membership의 remove 재시도는 추가 효과 없이 성공한다.
  종료된 과거 ID를 다른 활성 소속으로 바꿔 찾지 않는다. 다른 값의 오래된 버전은 409다.
  나가서 LEFT인 대상에게 처음 remove를 요청하면 이미 나간 대상 오류로 처리하고 목록을 갱신한다.
- 인계·나가기·내보내기는 번치 잠금 아래 직렬화하고, 사진/초안 정리가 실패하면 소속·역할·초대까지 롤백한다.
  내보내기는 이전 초대 링크와 보조 코드를 함께 폐기하고 새 초대 하나를 만든다.
  LEFT·REMOVED 모두 유효한 초대와 새 참여 요청 키로 재참여할 수 있다. 기존 초대 확인/참여 API를 재사용하고 별도 복구 API를 만들지 않는다.
  새 Membership은 항상 MEMBER이며 현재 가입 시각으로 생성한다. 이전 관리자 권한·종료된 소속을 되살리지 않는다.
  남아 있는 발행본의 이름·프로필·코멘트 연결은 동일 memberId와 현재 활성 소속으로 다시 표시한다.
  삭제된 Photo·업로더 연결·초안·초안 코멘트·편집권은 복구하지 않는다. 과거 참여 성공 요청 재생은 재참여를 실행하지 않는다.
- 계정 탈퇴의 자동 후임 선택은 별도 #29 계약에서 발행 기여 횟수 DESC→실제 활동 DESC→가입일 ASC→소속 ID ASC다.
  선택 대상에 OWNER/CO_OWNER/MEMBER의 다른 활성 소속을 포함하고 한 명을 OWNER로 승격한다.
  일반 나가기 API에는 자동 승계를 제공하지 않는다.
- confirmation은 trim 후 정확히 `삭제하기`여야 한다. 삭제 확인 화면은 home·memberships 조회만 사용하며 실행 전에는 데이터를 변경하지 않는다.
  실행 시 현재 OWNER와 membershipVersion을 검증하며, 실패하면 번치의 어떤 기록도 삭제하지 않는다.
- 삭제 성공은 번치/사진함/추천/초안/발행본/소속/초대/사회적 관계의 DB 삭제와 접근 차단 완료를 뜻한다.
  외부 파일 정리는 이후 수행한다. 삭제자 외의 멤버에게 안내와 푸시 전송 대기를 남긴다.
  안내·요청 성공 기록·전송 대기는 번치 FK cascade에 포함하지 않는다.
- 이탈/삭제 응답의 defaultBunchId는 남은 활성 소속의 최근 사용 순서로 계산해 해당 소속을 갱신한다.
  없으면 null이다. 같은 키 재생 시에도 현재 유효한 도착지를 계산하지만 이탈·삭제 결과는 반복하지 않는다.
  뒤의 화면 로딩 실패는 이미 성공한 이탈을 되돌리지 않는다.

## 6. 다른 번치 프로필·팔로우·차단·신고

외부 조회의 `viewerBunchId`는 선택한 현재 번치다. 주어졌으면 요청자의 활성 소속이어야 한다.
소속 0개 사용자만 생략할 수 있고 공개 탐색만 허용한다. 자기 소속인 다른 번치를 보더라도
대상 소속 권한을 합치지 않는다. viewer=target이면 앱이 자신의 홈으로 이동한다.

| 메서드·경로 | 입력 | data |
| --- | --- | --- |
| GET /bunches/{targetId}/public-profile | `viewerBunchId?` | PublicProfile |
| GET /bunches/{targetId}/public-members | `viewerBunchId?` | `{items:PublicMember[]}` |
| GET /bunches/{targetId}/public-bundles | `viewerBunchId?,page,size` | PublicBundleCard 페이지 |
| GET /bunches/{targetId}/public-bundles/{bundleId} | `viewerBunchId?` | PublicBundleDetail |
| GET /bunches/{targetId}/followers | `viewerBunchId?,page,size` | RelationItem 페이지 |
| GET /bunches/{targetId}/following | `viewerBunchId?,page,size` | RelationItem 페이지 |
| PUT /bunches/{viewerId}/following/{targetId} | 없음 | `{followState,followId}`; 공개는 FOLLOWING, 비공개는 REQUESTED |
| DELETE /bunches/{viewerId}/following/{targetId} | query `followId` | `{followState,followId,cancelled}`; cancelled는 FOLLOWING / REQUESTED / NONE |
| GET /bunches/{viewerId}/follow-requests | `page,size` | `{id,bunch,requestedAt}` 페이지 |
| POST /bunches/{viewerId}/follow-requests/{requestId}/decision | `{decision:"APPROVE" 또는 "REJECT"}` | `{requestId,applied,state}` |
| GET /bunches/{viewerId}/blocks | `page,size` | `{id,bunch,blockedAt}` 페이지 |
| PUT /bunches/{viewerId}/blocks/{targetId} | 없음 | `null` |
| DELETE /bunches/{viewerId}/blocks/{targetId} | query `blockId` | `null` |
| POST /bunches/{viewerId}/reports | `{targetBunchId,reason,detail?}` | `{reportId}` |

### 외부 응답과 접근

- PublicProfile은 `{bunch,stage,followerCount,followingCount,memberCount,
  followState,followId,canViewBundles,canViewMembers,canViewRelations,isCurrentBunch}`다.
  followId는 대기/승인된 관계 행 ID이며 NONE일 때 null이다. 대기 목록의 requestId도 이 ID다.
  bunch는 introduction을 포함한다. stage는 내부와 같은 전체 PUBLIC/PRIVATE 발행 수 기준이다.
  외부 프로필에는 별도 번들 수 필드를 반환하지 않는다. 승인되지 않은 비공개 번치에도 이 최소 정보를 반환하되
  번들 실제 목록·멤버 명단·팔로우 관계 목록은 제공하지 않는다.
- PublicMember는 `{memberId,nickname,imageUrl,role}`만 반환하고 OWNER→CO_OWNER→MEMBER 정렬을 쓴다.
  새 시트가 허용한 역할 배지는 공개하지만 출생연도·성별·가입일·소속 관리 action·세션은 공개하지 않는다.
- 공개 번치 또는 승인된 팔로워만 공개 범위 PUBLIC인 발행본과 멤버·관계 목록을 볼 수 있다.
  비공개 미승인 목록 요청은 403이며 PublicProfile의 접근 flag로 화면을 그린다.
  사진함·초안·PRIVATE 발행본·편집권은 어떤 외부 DTO에도 포함하지 않는다.
- PublicBundleCard는 `{id,bunchId,title,coverImageUrl,date,location,publishedAt}`,
  location은 null 또는 `{name,latitude,longitude}`이며 정밀 원본 EXIF 좌표를 반환하지 않는다.
  PublicBundleDetail은 카드에 `{photos,contributors,comments}`를 추가한다.
  photos는 `{id,imageUrl,position,caption}`, contributors는 `{memberId,nickname,imageUrl}`,
  comments는 `{id,memberId,nickname,imageUrl,content,updatedAt}`다. 이 comments는 참여 멤버 코멘트다.
  번치 명의 소셜 댓글·좋아요의 쓰기 API는 별도 피드 계약이며 이 문서에서 중복 정의하지 않는다.
  나간/탈퇴한 작성자 표시는 기존 익명화 규칙을 적용하고 탈퇴한 코멘트 원문을 노출하지 않는다.
  동일 계정으로 번치에 재참여하면 남아 있는 본인 기여·코멘트의 닉네임·이미지는 현재 개인 프로필로 복구한다.
  작성자 연결은 memberId로 유지하며 같은 코멘트/기여를 새로 생성하지 않는다. 서비스 탈퇴 후 새 계정과는 연결하지 않는다.
- 차단은 양쪽 어느 방향이든 외부 조회를 BUNCH_009로 통합하고 데이터·차단 방향을 반환하지 않는다.
  승인·공개 범위·차단·운영 숨김·삭제는 **상세 요청 때도** 다시 확인한다.
- 본인이 속한 다른 번치에도 viewer 명의 행동이 가능하다. 자기 번치(viewer=target)의 팔로우·차단·신고만 400이다.
  가입 완료·소속 0개 사용자는 공개 조회만 하며 명의 행동에는 BUNCH_010을 반환한다.

### 관계 변경·집계

- 팔로우·요청 승인/거절·신고는 viewer의 모든 활성 멤버가 가능하다. 차단/해제는 OWNER/CO_OWNER만 가능하다.
- 팔로워/팔로잉은 승인된 관계만 센다. 각 목록과 수는 viewer와의 차단으로 걸러 같은 기준을 적용한다.
  관계 정렬은 followedAt DESC, relation ID DESC; 차단은 blockedAt DESC; 요청은 requestedAt DESC다.
- 한 쌍에는 승인 관계 1개 또는 대기 요청 1개만 가능하다. 취소 뒤 재요청은 새 requestId(관계 행 ID)다.
  오래된 승인/거절 요청은 새 요청에 적용하지 않는다. 이미 처리된 requestId에는 먼저 처리된 상태를
  `{applied:false,state:APPROVED}` 또는 `{applied:false,state:UNAVAILABLE}`로 반환하며 다른 결정을 덮어쓰지 않는다.
  최초 승인 성공은 applied=true/state=APPROVED, 최초 거절 성공은 applied=true/state=REJECTED다.
  삭제된 요청의 과거 거절/취소 이력을 복원하려 별도 상태 원장을 만들지 않는다.
- 요청 취소 전에 같은 followId가 승인됐다면 DELETE following은 맺어진 팔로우를 해제하고 cancelled=FOLLOWING을 반환한다.
  followId가 없어졌거나 다른 ID의 새 관계이면 변경하지 않고 현재 상태와 cancelled=NONE을 반환한다.
  승인/거절 처리 중 상대가 취소·삭제된 경우도 applied=false로 처리해 앱에서 행만 제거한다.
- 차단은 방향 있는 관계로 저장하되 접근 제한은 양방향이다. 두 방향의 팔로우와 대기 요청을 같은
  트랜잭션에서 해제한다. 현재 blockId와 일치하는 자신의 차단만 해제한다. 사라진/이전 blockId는 no-op이며 새 차단을 대신 지우지 않는다. 상대의 차단이 남으면 계속 볼 수 없다.
  해제로 과거 팔로우를 복구하지 않는다. 차단 API는 상대의 차단 여부를 응답하지 않는다.
- reason은 SPAM / INAPPROPRIATE / HARASSMENT / PERSONAL_INFORMATION / OTHER다.
  detail은 trim 후 최대 200 grapheme, OTHER이면 1자 이상 필수다. 그 외에는 없으면 null이다.
  같은 viewer·대상 번치의 재신고는 첫 신고 ID를 200으로 반환하고 운영 접수를 늘리지 않는다.
  최초 사유·상세는 보존한다. 신고는 viewer 피드에서 대상 콘텐츠를 숨기며 자동 운영 제재나 차단은 하지 않는다.
  운영 검토 화면·처분 API는 별도 운영 범위다.

## 알림 연계

알림·읽음·개인 설정·기기·APNs API와 NotificationTarget은 [알림 API](notification-api.md)를 따른다.
번치 홈·전환의 unreadCount와 접근 불가 안내는 해당 알림·전송 계약에 연결한다.

## 7. 요청·응답 연결 예시

아래는 계약 확인용 가상 ID다. 프로필·인계·나가기·번치 삭제 요청에만 요청 키 헤더를 보낸다.
principal은 인증에서 얻고 body의 actorMembershipId와 일치하는 소속을 서버에서 확인한다.

`POST /api/v1/bunches/42/owner-transfer` 요청:

```json
{"actorMembershipId":100,"expectedMembershipVersion":12,"targetMembershipId":101}
```

성공 응답은 인계가 완료된 최소 결과다. 현재 권한으로 멤버 목록을 다시 조회해 화면을 갱신한다.
같은 키를 재생할 때 아래 결과를 다시 반환해도 새로운 인계를 실행하지 않는다.

```json
{"success":true,"data":{"bunchId":42,"membershipVersion":13,"changedMembershipIds":[100,101],"ownerMembershipId":101},"error":null}
```

메인 관리자가 후임을 지정하고 나가는 `POST /api/v1/bunches/42/leave` 요청:

```json
{"actorMembershipId":101,"expectedMembershipVersion":13,"successorMembershipId":102}
```

```json
{"success":true,"data":{"bunchId":42,"departedMembershipId":101,"outcome":"LEFT","defaultBunchId":null},"error":null}
```

다른 멤버가 내보내진 뒤 푸시의 noticeId로 조회한 안내:

```json
{"success":true,"data":{"id":700,"bunchId":42,"name":"퇴근런클럽","characterType":"TYPE_1","reason":"REMOVED","occurredAt":"2026-10-29T09:00:00Z","expiresAt":"2026-11-28T09:00:00Z"},"error":null}
```

## 8. 클라이언트 호환성과 수용 기준

- 신규 API는 해당 기능 서버와 함께 공개한다. 기존 API는 같은 경로를 유지하되 역할·Stage·초안 정렬의
  의미가 바뀌므로 앱과 함께 전환한다. `CO_OWNER`를 이해하지 못하는 구버전 서버와 혼합 운영하지 않는다.
- #32 BundlePage 응답에 공통 hasNext를 추가한다고 가정하지 않는다. 기존 페이지 DTO는 보존하며
  상세 shape는 #32의 최종 병합 계약을 확인한다. 기존 목록은 status별 권한 변경만 적용한다.
- 자동 검증: 정상/권한 오류/가입 중/세션 장애, 같은 키·다른 입력, 인계/이탈 후 성공 재생,
  재참여 소속에 대한 과거 요청 거부, 이름 선점/버전 경합/이미지 보상, 공개 전환·차단·요청 처리 경합,
  업무와 알림 저장 연계를 포함한다. 읽음·리마인드·기기·APNs 검증은 [알림 API](notification-api.md#5-검증앱-연동)를 따른다.
- 별도 iOS 수동 검증: 원본 선택·JPEG 변환·모달·목록 갱신·번치 전환·삭제 대상 안내다.
  기기별 푸시·APNs 수동 검증은 [알림 API](notification-api.md#5-검증앱-연동)가 소유한다.
  문서 예시 파싱과 자동 테스트만으로 이 수동 검증을 완료했다고 표시하지 않는다.
