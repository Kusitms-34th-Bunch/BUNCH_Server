# 번치 이탈·접근 불가 안내 구현 계약

이 문서는 #25 나가기·내보내기와 #38 사진 업로더 정리의 구현 연결을 기록한다.
기준 HEAD는 #57 보정이 포함된 `8fbf45a`이며, V16은 #49의 `BunchActionRequest`/
`BunchActionRequestRepository`를 merge해 재사용하고 이 기능에서는 복제하지 않는다. 푸시
경계는 #60 소셜 선행 뒤 검증된 #65 `2f52c7e`를 병합해 사용하며, 이탈 기능은 그 writer
호출부만 연결한다.

## API와 상태

- `POST /api/v1/bunches/{bunchId}/leave`는 `Idempotency-Key`와
  `actorMembershipId`, `expectedMembershipVersion`, 선택적인
  `successorMembershipId`를 받는다.
- `POST /api/v1/bunches/{bunchId}/memberships/{membershipId}/remove`는
  actor와 기대 membership version을 받는다. OWNER는 CO_OWNER/MEMBER를,
  CO_OWNER는 MEMBER만 내보낼 수 있다. 자기 자신·OWNER 대상은 허용하지 않는다.
- OWNER가 직접 나갈 때 후임을 명시해야 하며, 유일한 활성 멤버이면
  `BUNCH_007`, 후임 누락이면 `BUNCH_006`을 반환한다. 계정 탈퇴 자동 승계는
  #29의 별도 흐름이다.
- 나가기는 `LEFT`, 내보내기는 `REMOVED`로 종료한다. 두 경우 모두 새 유효 초대와
  새 참여 키로만 새 `MEMBER` Membership을 만든다. 이전 join key의 성공 요청은
  종료된 Membership을 재생하지 않는다.

## 원자성·잠금·사진 경계

회원(내보내기는 actor/target ID 오름차순) → Bunch → Bundle/Photo 순으로 잠근다.
OWNER 인계는 actor를 `MEMBER`로 바꾸고 flush한 뒤 후임을 `OWNER`로 바꾸고 flush해
활성 OWNER 0명/unique owner 제약 경합을 피한다. membership version과 초대 회전,
notice/알림, Membership 종료는 같은 DB transaction에서 커밋한다.

이탈 업무는 기존 `BundleService.memberDeparted`와 `PhotoService.removeAllUploaders`
를 호출한다. 마지막 업로더 삭제는 기존 `PhotoFinalDeletionCallback`과
`BundlePhotoDeletionCallback`을 재사용하므로 발행본의 image asset·caption·순서는
보존하고, 초안 참여·코멘트·편집권과 빈 초안은 정리한다. callback/정리 실패는
회원·역할·초대·안내를 포함해 전체 rollback한다. 외부 파일 삭제와 socket/APNs는
commit 뒤 경계에 남긴다.

`BundleRealtime*`, `BundleSocketController`, `BundleEditLease`/Lease 구현은 수정하지
않았다. Lease 제거는 기존 `memberDeparted`/photo callback에 위임한다.

## AccessNotice와 내부 접근 확인

V17 `bunch_access_notices`만 이 기능에서 생성한다. `bunch_id`와 `membership_id`는
본체 삭제 뒤에도 30일 동안 안내를 열 수 있도록 FK 없이 보관하며, recipient member
FK·만료 index·recipient/membership/reason unique를 둔다. 직접 나가기 안내는 만들지
않고, 내보내기와 #50 삭제가 `REMOVED`/`DELETED`를 같은 모델로 저장한다.

조회·삭제는 활성 계정과 가입 완료를 먼저 확인하고, 본인 notice만 허용한다. 유효한
notice는 만료 조회에서 숨기고 hourly retention이 물리 삭제한다. 재참여는 새
Membership 저장과 같은 transaction에서 해당 member/bunch의 과거 notice를 지운다.

현재 활성 Membership이 없고 본인의 유효 notice가 있으면 내부 bundle/members 접근
확인 경계가 `BUNCH_008`과 `{noticeId,reason}` detail을 반환한다. notice가 없으면
기존 `FORBIDDEN`을 유지한다. Bunch 본체가 이미 삭제된 경우에도 `lockBunch`가
404를 먼저 내지 않도록 확인 경계를 먼저 호출한다. 범용 permission framework는
추가하지 않았다.

## 알림·푸시·재참여 통합

OWNER 직접 나가기는 기존 `NotificationWriter.ownerTransferred`가 `OWNER_TRANSFERRED`
inbox row를 저장하고, 현재 `NotificationWriter`의 `saveNotifications`가 같은
transaction에서 수신 기기별 type 13 job을 만든다. 실행자에게는 보내지 않는다.
내보내기는 app `Notification` row를 만들지 않고 `AccessNotice`를 먼저 기록한 뒤
`PushJobWriter.enqueueDetached`로 대상 회원의 현재 허용 기기에만 type 14 detached job을
만든다. job의 `notification_id`는 null이고 payload target은 `ACCESS_NOTICE`와 현재
notice ID를 담으며, body는 승인 원문인 `번치 멤버 목록에서 제외되어 더 이상 번치에
참여할 수 없어요.`를 그대로 사용한다. 번치 본체 FK를 요구하지 않는다.

나가기·내보내기는 `cancelCommentRemindersForBunchLeave`를 같은 transaction에서 호출한다.
재참여는 새 Membership 저장 후 `cancelPendingRemovalNoticeForRejoin`과
`removeForRejoin`을 같은 transaction에서 실행하므로 과거 removal 안내와 대기 job을 함께
취소한다. Push job insert/cancel 실패는 Membership·role·version·photo/notice·초대와 함께
rollback하며 fake push나 APNs 호출을 만들지 않는다. 재생 요청은 업무와 job을 재생성하지
않는다.

사진 파일 API 변경은 추가하지 않았다. #46 `c04ecb6`의 `PhotoService.deleteLocked`
추출/`deletePhoto`가 먼저 통합되면 이 브랜치의 member-wide uploader 정리와 같은
final-delete 블록을 대조해 충돌을 해소해야 한다. #45의 PhotoRepository 목록 함수는
보존한다. 최종 PR에서는 #46 선행 변경과 final-delete 블록을 대조하고, 타 사진 API는
확장하지 않는다.

#50/#52는 `BunchAccessNotice`, repository, service의 `recordRemoval`/`recordDeletion`/get/delete/active/removeForRejoin
계약을 재사용한다. 번치 본체 삭제·홈/목록의 호출부는 각각 자기 transaction에서
동일한 active/completed/expiry/재참여 숨김 규칙을 유지해야 한다.
