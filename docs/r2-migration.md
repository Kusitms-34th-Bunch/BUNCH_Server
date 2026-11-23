# Cloudinary 원본 전체 → R2 전환 (#213)

이 문서는 실행 준비용이다. 실제 이전·스키마 전환·배포·Cloudinary 삭제는 이 변경에서 실행하지 않는다.
2026-11-23 조정 채팅은 두 로컬 입력 파일의 R2 필수값 준비를 비밀값 없이 확인했다. 키 준비는 실행 승인이 아니다.
사용자 최종 확정: 별도 빈 운영 DB·Redis·`bunch-prod`를 준비하고 추후 `api.bunch.kr`을 운영에 연결한다.
현재 개발 DB/이미지 데이터는 운영에 승격·복제하지 않는다. 기존 도메인 연결은 유지하며 dev 도메인 일정은 미확정이다.
현재 개발 데이터의 R2 이전은 짧은 점검 일정 확정 후 수행한다. 실제 환경 준비·호출·업로드·이전·배포·DNS 변경은
조정 채팅이 관리하며 이 작업에서는 실행하지 않는다. Cloudinary 삭제도 실행하지 않는다.
최종 앱은 R2만 사용하며 Cloudinary 자격 증명은 일회성 도구 프로세스에만 전달한다.

## 저장·환경 계약

- `R2_ENDPOINT=https://67e6dbcbc1d362cf8d959c8b3131593f.r2.cloudflarestorage.com` (버킷 제외).
- 개발 버킷 `bunch-dev`, 운영 버킷 `bunch-prod`. 기존 Cloudinary 전체 원본은 `bunch-dev` 이전 대상이다.
  운영은 별도 빈 DB·Redis·버킷으로 시작하며 개발 데이터·세션·이미지를 승격하거나 복제하지 않는다.
- 앱 환경은 `R2_ENDPOINT`, `R2_BUCKET`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY` 네 값이다.
  두 버킷은 private, r2.dev·공개 Custom Domain은 비활성 상태로 유지한다.
- 신규 사진은 `photos/<UUID>/original.jpg` + `thumbnail.jpg`, 프로필은
  `profiles/<UUID>/profile.jpg`다. 이전 자산은 UUID 대신 기존 asset ID를 사용한다.
- DB에는 `image_assets.object_key`만 저장한다. ID/FK/사진 해시는 그대로다.
- 보관본 바이트는 변경하지 않는다. ImageIO는 EXIF 방향을 반영한 300px 중앙 정사각형 JPEG
  품질75 썸네일을 한 번 생성하며 확대하지 않는다. 썸네일 큐·Worker·Images·provider 인터페이스는 없다.
- 기존 권한 검사 뒤 응답 시 GET을 로컬 서명한다(1800초). 목록·카드·피드는 썸네일, 상세는 보관본이다.
  기존 필드명과 null 계약은 유지한다. 만료되면 앱은 기존 조회 API를 다시 호출한다.

## 최신 dev와 마이그레이션 순서 (2026-11-23)

현재 통합 기준은 `origin/dev a00d34cdc2cf6996acdab801d76966acddca09c3`다. 이미 머지된
#215의 `V53__notification_deep_link_fields.sql`은 원문·체크섬·적용 이력을 유지한다.
R2의 미적용 신규 파일은 `V55__r2_image_objects.sql`이다. 기존 R2 V53은 로컬 개발 산출물이었으며
운영 이력을 rename/repair하는 절차가 아니다. 이전 검증 DB에는 새 번호를 억지로 적용하지 않고 새
전용 DB로 재검증한다.

| 대상 | 확인한 번호/상태 | 이 작업의 처리 |
| --- | --- | --- |
| #215 / dev `a00d34c` | V53 알림 deep link, 머지 완료 | 코드·기존 알림 계약을 함께 통합, 파일 변경 없음 |
| #217 `22cd7c3` | V54 촬영 밀리초·기기 모델·크기, OPEN | 번호만 예약 고려, 기능·파일 미통합 |
| #219 `41cb95e` | #217 변경과 같은 V54를 포함, OPEN | 중복된 V54를 새 migration으로 간주하지 않음, 미통합 |
| #221 `f484810` | 같은 V54를 포함, OPEN | 미통합 |
| #224 `2b78e9f` | 같은 V54를 포함, OPEN | 미통합 |
| #213 / 이 변경 | V55 R2 | 기존 자산 전환 조건을 확인한 뒤 적용 |

**#217의 V54가 적용될 환경에는 V54 → V55 순서로 배포한다.** Flyway 기본 설정에서 V55를 먼저
적용한 후 V54를 추가하면 V54가 무시되거나 validate가 실패할 수 있다. 따라서 권장 통합 순서는
#217 → 최신 dev를 #213에 반영하고 겹치는 코드·테스트 조정 → #213이다. 후속 추천 PR은 head ancestry 확인상
#217 → #219 → #221 → #224 순서이며 병합 시 다시 확인하고 R2 경로/서명을 보존해 통합한다. 적용된 migration을
다시 번호 변경하거나 `outOfOrder`/repair를 켜서 순서를 우회하지 않는다. #217을 채택하지 않는
환경의 현재 소스는 번호 54를 만들지 않으며 빈 DB는 포함된 V53 → V55를 정상 적용한다. 이후
V54 기능을 채택하기 전 별도 순서 조정이 필요하다. 지금은 #217의 기능을 가져오지 않았다.

#217과 겹치는 지점은 `PhotoService`의 `upload/saveUpload/joinUpload`, `PhotoRepository`의 projection과
촬영일 초기화, `PhotoUploadTestSupport`, 사진함 Spec이다. 이후 통합 시 다음을 함께 유지한다.

- #217의 `capturedMillis/deviceModel`, `Photo.width/height`는 보관본 `PhotoImage`에서 얻은 값이다.
  썸네일의 300px 크기를 사진의 보관본 해상도로 저장하지 않는다.
- R2의 `StoredImage.objectKey`, `ImageAsset.objectKey`, projection `imageKey`, 권한 검사 뒤 응답 시
  `ImageUrls` 서명과 보관본 원본 해시를 유지한다. `publicId/url`로 되돌리지 않는다.
- #217의 없는 값만 채우기·촬영일 삭제 시 millis 초기화와 R2의 외부 I/O 트랜잭션 경계·중복 업로드
  보상 삭제를 모두 보존한다. 문서와 fixture는 두 계약을 함께 반영하고 새 전체 build로 검증한다.
- V54가 실제 통합되면 R2 전환 테스트의 pre-R2 기준도 V54로 올리고 현재 소스의 V54 부재
  assertion을 실제 V54 이력 보존 assertion으로 바꾼다. 기존 V53·새 V54 원문은 수정하지 않는다.

## 로컬 도구 준비

JDK21과 현재 저장소의 Gradle 의존성을 재사용한다. 새 Python/이미지 라이브러리는 필요 없다.
`run.sh`는 `.env`를 읽지 않으며 실제 호출은 명시적 live/write 옵션이 있어야 실행된다.
원본 목록·manifest·DB dump는 저장소 밖의 접근 제한 디렉터리에 두고 버전 관리하지 않는다.

```bash
scripts/r2/run.sh --help
scripts/r2/run.sh test
```

실행 프로세스에만 `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET`,
`DB_URL`(JDBC), `DB_USERNAME`, `DB_PASSWORD`, R2 네 변수를 제공한다. 비밀값을 명령행 인자,
로그, 채팅에 쓰거나 서버 `.env`를 복사하지 않는다. `DB_SCHEMA`는 기본 `public`이다.
다른 앱 작업과 공유하지 않는 임시 세션/secret injection을 사용한다.

## 기존 DB의 정확한 순서

1. 현재 서버 이미지/커밋·설정 복구본과 전체 PostgreSQL dump를 확보한다. `bunch-pgdata`와
   `bunch-redis-data` 볼륨은 보존한다. 새 앱을 먼저 배포하면 V55가 준비되지 않은 기존 DB를 거부한다.
2. 기존 앱이 동작하는 상태에서 `scripts/r2/prepare.sql`을 대상 DB에 적용한다.
   `MIGRATION_PG_URL`은 JDBC가 아닌 PostgreSQL 접속 URI이며 비밀번호는 `PGPASSFILE`로 주입한다.

   ```bash
   pg_dump --format=custom --no-owner --file=/secure/r2/before-r2.dump "$MIGRATION_PG_URL"
   psql -X --set=ON_ERROR_STOP=1 --dbname="$MIGRATION_PG_URL" --file=scripts/r2/prepare.sql
   ```

   nullable `object_key` 추가만 수행하므로 기존 앱은 계속 `public_id`/`url`을 사용한다.
3. 개발 전용 도구 자격 증명으로 전체 원본 목록과 DB 참조를 대조하는 plan을 만든다.

   ```bash
   scripts/r2/run.sh --mode inventory --live --output /secure/r2/inventory.json
   scripts/r2/run.sh --mode plan --live --output /secure/r2/precopy.json
   scripts/r2/run.sh --mode copy --live --apply --confirm-write --manifest /secure/r2/precopy.json
   ```

   Search API의 모든 delivery/resource type 페이지를 끝까지 열거하고 total_count를 대조한다.
   upload/private/authenticated는 Admin API 전체 페이지와도 대조한다. 검색 색인이 지연되면
   전환을 거부하므로 색인 반영을 기다린 뒤 다시 실행한다. PDF 등 resource_type=image인
   비사진 형식도 별도 분류한다. 비이미지 여부는 실제 inventory/manifest에서 확인한다.
   실패 분류·누락 원본·참조된 비이미지나 기존 업로드 계약 밖의 비JPEG는 수정 전 전환 금지다.
   이들은 archive 누락 없이 분류하지만 서버의 JPEG 자산으로 자동 재인코딩하지 않는다. DB 미참조 원본도
   `archive/assets/<id>/<identity sha256>/original.<format>` 또는
   `archive/orphans/<identity sha256>/original.<format>`에 보존한다. 변환 캐시는 복사하지 않는다.
   원본/타깃 GET SHA-256을 비교하며 ETag를 해시로 가정하지 않는다. 실행 중 원본이나 DB가
   바뀌면 stale plan을 거부하므로 최신 plan을 다시 만든다. 같은 manifest의 copy는 재실행 가능하다.
4. 점검 시작: API ingress/웹소켓 쓰기·업로드와 관련 async/background를 모두 중단하고 기존 앱을
   정지한다. Cloudinary 관리 콘솔의 쓰기도 중단한다. 최종 DB dump를 추가 확보한다.
5. 최신 전체 목록·DB snapshot으로 최종 plan을 만들고 차이를 복사·검증한다. 아래 명령의
   마지막 cutover는 R2 전체 대상 검증 후 DB 경로를 한 트랜잭션으로 채운다.

   ```bash
   scripts/r2/run.sh --mode plan --live --previous-manifest /secure/r2/precopy.json --output /secure/r2/final.json
   scripts/r2/run.sh --mode copy --live --apply --confirm-write --manifest /secure/r2/final.json
   scripts/r2/run.sh --mode cutover --live --apply --confirm-write --confirm-freeze --manifest /secure/r2/final.json
   ```

   `--previous-manifest`는 source identity/asset ID/version/크기/ETag/format/분류/경로가 같은
   VERIFIED 결과만 이월한다. copy는 해당 대상의 GET SHA-256을 다시 확인하고 Cloudinary 재다운로드·PUT을
   생략한다. 새롭거나 바뀐 원본만 복사한다. 최종 cutover는 모든 대상과 남아 있는 Photo.file_hash도
   재검증한다. Photo 행이 이미 삭제된 발행본 전용 자산은 원본↔R2 해시로 검증한다.
   최종 쓰기 중단 동안 원본 목록·DB 참조가 변하면 다시 plan/copy한다. 실패한 cutover는 DB mapping을
   롤백한다. 아직 V55 전이므로 기존 URL/public ID는 보존되어 있다.
6. 앱 환경에 개발 R2 네 값을 넣고 Cloudinary 세 변수는 앱 환경에서 제거한다. R2 앱을 배포한다.
   V55는 모든 자산의 canonical 경로와 참조 경로를 확인하고 기존 URL/public ID 컬럼을 제거한다.
   이미 적용된 Flyway는 수정하지 않는다. JPA validate가 통과한 뒤 읽기 smoke를 진행한다.
7. 권한별 사진함/상세/프로필/초대/추천/번들/피드/소셜/예외 detail/WebSocket/멱등 재응답을 확인한다.
   서명 만료1800초·path 변조/만료 거절, anonymous GET 거절, r2.dev/공개도메인 우회 불가를
   실제 R2에서 확인한다. iOS 조회·새로고침·기존 API 재호출도 별도 확인한다.
8. 성공 확인 후 쓰기·background를 재개한다. 원본은 삭제하지 않는다. 보관 기간 후 원본 삭제나
   계정 해지는 별도 파괴적 정리 작업이다.

## 실패와 복구

- 사전 복사/최종 검증 실패: 기존 앱/DB를 유지한다. 이미 복사한 R2 객체는 다음 실행에서 검증하여
  재사용한다. 원본은 변경·삭제하지 않는다. 대상 불일치는 자동 덮어쓰지 않고 원인을 확인한다.
- DB mapping 트랜잭션 실패: 자동 rollback. 쓰기 중단 상태에서 원인을 수정하고 재시도하거나 기존
  앱을 그대로 재개한다.
- mapping 성공, V55 전 실패: 기존 컬럼이 남아 있으므로 기존 서버 이미지·Cloudinary 설정으로
  복구 후 읽기 확인하고 재개할 수 있다. object_key는 기존 앱이 읽지 않는다.
- V55 또는 새 앱 배포 후 실패: 쓰기를 계속 차단한다. 점검 직전 전체 DB dump를 새 복구 DB에
  `pg_restore --exit-on-error --no-owner --dbname=<복구DB> <dump>`로 복원하고, 기존 서버 이미지와
  기존 DB 연결·Cloudinary 설정으로 되돌린 뒤 FK/샘플 조회를 검증한다. 원래 볼륨을 지우지 않는다.
  Flyway repair/과거 migration 수정/체크섬 조작으로 되돌리지 않는다. 검증 후에만 ingress/background를
  재개한다. R2에 새 쓰기를 허용한 이후에는 해당 쓰기를 별도로 보존·조정해야 하므로 자동 롤백하지 않는다.
- manifest/dump/복구 설정을 성공 검증까지 보관한다. 실제 secret은 이 문서/manifest에 넣지 않는다.

## 빈 운영 환경과 #212 연계

별도 빈 운영 DB·Redis·`bunch-prod`를 미리 준비하는 것으로 확정했다. 이후 `api.bunch.kr`을 운영에 연결한다.
기존 도메인 연결은 그대로 두며 dev 도메인과 개발 R2 전환 점검 일정은 별도로 확정한다. 도메인 변경 없이도
운영 앱의 내부 기동 준비와 코드 통합·로컬 검증은 진행할 수 있다. 실제 준비·배포는 조정 채팅이 순차 수행한다.

1. #212 담당은 운영 전용 빈 DB·Redis와 private `bunch-prod`를 연결한다. `DB_URL`과 DB 자격 증명,
   `REDIS_HOST/REDIS_PORT`를 운영 대상으로 명시하고 개발 저장소를 가리키지 않는지 확인한다.
   개발 DB dump·이미지·Redis 세션은 가져오지 않는다.
2. 앱에는 `R2_BUCKET=bunch-prod`와 운영 접근 권한의 R2 네 값을 전달하고 Cloudinary 변수를 제거한다.
   API 도메인과 R2 endpoint는 별개다. 버킷을 공개하거나 R2 endpoint를 `api.bunch.kr`로 바꾸지 않는다.
3. 빈 DB는 현재 포함된 V1~V53 및 V55를 직접 적용한다. 준비 SQL·Cloudinary inventory/copy/cutover는
   운영에 실행하지 않는다. 빈 `image_assets`는 V55의 기존 자산 mapping 요구를 만족하며 최초 실행 후
   Flyway validate·재실행이 가능하다. #217을 통합할 경우 앞 절의 V54 → V55 순서를 지킨다.
4. 별도 운영 연결을 사용하는 앱의 DB/Redis 연결·JPA validate·내부 health 확인을 마친 뒤 도메인 전환은
   추후 일정에 수행한다. 실제 업로드·권한·서명 검증은 조정 채팅이 관리하는 smoke 단계에서 수행한다.

현재 개발 데이터는 `bunch-dev`만 대상으로 위 사전 복사·짧은 점검 중 동결·최종 검증·V55·배포 순서를 따른다.
#212는 README·deploy/.env 예제·Compose·CI/서버 env 전달을 담당한다.
`deploy/**`, `.github/workflows/**`, README는 이 변경에서 수정하지 않는다.

## 검증 범위

자동 검증은 전용 로컬 PostgreSQL·Redis와 HTTP stub만 사용한다. 실제 계정 원본의 수/비이미지
유무·R2 권한/만료·iOS 렌더링·실제 DB 복구 소요시간은 조정 채팅의 실행 단계에서 확인한다.
SDK 설정 근거: [Cloudflare Java 예제](https://developers.cloudflare.com/r2/examples/aws/aws-sdk-java/),
[서명 URL](https://developers.cloudflare.com/r2/api/s3/presigned-urls/),
[Cloudinary 원본 열거](https://cloudinary.com/documentation/admin_api).
