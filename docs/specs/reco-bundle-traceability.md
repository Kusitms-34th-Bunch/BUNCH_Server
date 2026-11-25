# 추천 번들 조합 추적표

새 기준(추천번들조합_수정 1119, AI 미사용)의 항목이 코드와 테스트의 어디에 있는지 연결한다. 기준 문서는 [추천 번들 조합 계약](reco-bundle-combination.md)의
「새 기준」 절이고, 이 표는 이슈 #197에서 만들었다. 구현 이슈마다 해당 행의 상태·구현·테스트를 갱신한다.

상태는 다음처럼 적는다.

| 상태 | 뜻 |
| --- | --- |
| 구현됨 | 새 기준과 같게 이미 구현되어 있다 |
| 부분 | 일부만 같다. 차이는 비고에 적는다 |
| 교체 예정 | 구현이 있으나 새 기준과 달라 다시 쓴다 |
| 삭제 예정 | 새 기준에 없는 기능이다 |
| 미구현 | 구현이 없다 |

이슈 번호가 없는 "후속"은 아직 등록하지 않은 이슈다. 새 기준을 이전 판(조합명세 11-13)과 구분하려고 새 기준의 번호는 그대로 쓰고, 이전 판을 인용할 때는 「이전판」이라고 적는다.

## 추적표

### 사진에 붙일 정보 (새 기준 「사진에 붙일 정보」)

| 항목 | 새 기준 | 현재 구현 | 테스트 | 상태 | 이슈 / 비고 |
| --- | --- | --- | --- | --- | --- |
| F1 | 촬영 날짜·시각 | `RecoPhoto`, `RecoPhotoInputBuilder`, `TimeNormalizer` | `TimeNormalizerTest`, `RecoPhotoInputIntegrationTest` | 구현됨 | 하루 05:00 기준, 시간대 구분 포함 |
| F2 | 업로더 | `RecoPhoto.uploads`(함께 올린 사진은 한 장, 올린 멤버는 모두) | `RecoPhotoTest`, `RecoPhotoInputIntegrationTest` | 구현됨 | |
| F3 | 업로드 묶음 | `RecoPhoto`의 업로드 묶음·날짜 묶음 ID | `RecoPhotoInputIntegrationTest` | 구현됨 | 시각 없는 사진 붙이기는 X3 행 |
| F4 | 업로드 시각 | `uploads[].uploadedAt`(`photo_uploaders.uploaded_at`) | `RecoPhotoInputIntegrationTest` | 구현됨 | X5가 쓴다 |
| F5 | 원본 유형 | `PhotoSourceType`, 업로드 필드 `sourceType`, 입력 만들기에서 한 곳에서 판정 | `PhotoUploadOriginIntegrationTest` | 구현됨 | 값이 없으면 날짜·시각이 있을 때 카메라, 없으면 저장 이미지 |
| F6 | 품질 | `ImageQuality`(긴 변 1024px 회색조, 흔들림·노출·해상도), `PhotoRecoFeature.quality` | `PhotoUploadQualityIntegrationTest`, `PhotoRecoFeatureQualityMigrationIntegrationTest` | 구현됨 | D3·L3 법무 확인 대기. 업로드 때 동기 계산, 비동기 분리는 별도 이슈 |
| F7 | 기기 모델, 렌즈, 연사 ID, 밀리초 시각 | 업로드 필드 `deviceModel`(`photo_reco_feature.device_model`), `capturedMillis`(3자리, `photos.captured_millis`). R1용 해상도는 서버가 읽어 `photos.width`·`height`에 저장 | `PhotoUploadMillisModelIntegrationTest`, `PhotoMillisModelMigrationIntegrationTest` | 부분 구현(#204 저장, #218 추천 입력 연결) | 렌즈·연사 ID·EXIF 고유 ID는 받지 않는다(D4·L2 법무 대기). 추천 입력(`RecoPhoto`)에 연결하는 것은 후속. 앱 협의 필요 |
| R1 | 중복 묶음 | `DuplicateGrouper(TimeNormalizer, burstMaxGap)`: 밀리초+기기 모델+해상도가 모두 있고 같음, 같은 업로더·같은 기기 모델·2초 안의 연사. 이미지 지문은 쓰지 않는다. 같은 파일은 업로드 때 한 사진으로 합쳐짐 | `DuplicateGrouperTest`, `RecoPhotoInputIntegrationTest` | 구현됨(#218) | 값이 없으면 같다고 보지 않음(C8). 지문은 날짜 빌리기·시계 보정·카드 점수가 아직 써서 입력에 남아 있고, 그 단계의 재작성 이슈에서 삭제 |
| R2 | 함께 찍은 순간 | `TogetherMoments`(서로 다른 멤버의 쓸 수 있는 사진이 `togetherWindow` 안에 있는 순간. 같은 중복 묶음을 여러 멤버가 올렸으면 가장 먼저 올린 멤버 한 명의 사진으로 봄) | `TogetherMomentsTest` | 구현됨(#222) | 번들·나누기 판단에 연결하는 것은 #223, 사진 고르기·커버는 후속 |

### 시간 기준 (새 기준 「시간 기준」)

| 항목 | 새 기준 | 현재 구현 | 테스트 | 상태 | 비고 |
| --- | --- | --- | --- | --- | --- |
| 하루·시간대 | 05:00~다음 날 05:00, 오전·오후·밤 | `CombinationSettings`(`dayStartHour`, `afternoonStartHour`, `nightStartHour`), `TimeNormalizer` | `CombinationSettingsTest`, `TimeNormalizerTest` | 구현됨 | |
| 경계 20분 | 공백 20분 이상 | `CombinationSettings.minBoundaryGap` | `CombinationSettingsTest` | 구현됨 | 나누기 규칙은 아래 행 |
| 사람 바뀜 1시간 | 사람 바뀜 공백 | 없음 | — | 미구현 | 후속 |
| 시간대 바뀜 2시간 / 같은 시간대 4시간 | | `continuityShort`, `continuityLong`(활동 성격별로 씀) | `CombinationSettingsTest` | 교체 예정 | 새 기준은 시간대 기준으로 쓴다. 후속 |
| 함께 찍은 순간 30분 | | `CombinationSettings.togetherWindow`(30분, 정확히 30분은 포함) | `CombinationSettingsTest` | 구현됨(#222) | "30분 안"의 경계 포함 여부는 기획 확인 |

### 일반 번들 (새 기준 「일반 번들 기준」)

| 항목 | 새 기준 | 현재 구현 | 테스트 | 상태 | 이슈 / 비고 |
| --- | --- | --- | --- | --- | --- |
| 묶기·나누기 1~5순위 | 사람 바뀜 → 시간대 바뀜 → 같은 시간대 4시간 → 묶음 → 40장 분할 | `GeneralBundleFinder`, `GeneralSegmenter`, `GeneralChunker`, `GeneralPeopleTimeSplitter`, `GeneralBigSplitter`, `GeneralSectionBalancer`(활동 라벨 기반) | `GeneralBundleFinderTest` 등 | 교체 예정 | 후속 |
| 가장자리 정리 | 맨 앞·뒤의 한 사람 구간 떼어 냄 | 없음 | — | 미구현 | 후속 |
| 추천 조건 | 6장 + 2명 + 함께 찍은 순간 | `BundleEligibility.isRecommendable`(성립 조건 `isEnough` + 함께 찍은 순간 1번 이상) | `BundleEligibilityTest`, `TogetherMomentsTest` | 계산 구현됨(#222), 번들 반영은 후속 | 번들에 연결하고 "활동 미상은 번들이 아님" 규칙을 지우는 것은 #223 |
| 이벤트 번들 | 만들지 않음 | `EventFinder` 등 | `EventFinderTest` 등 | 삭제됨 | #199 |

### 사진 고르기와 이름 (새 기준 「사진 고르기」)

| 항목 | 새 기준 | 현재 구현 | 테스트 | 상태 | 이슈 / 비고 |
| --- | --- | --- | --- | --- | --- |
| 1순위 제외 | 계산 제외·스크린샷·저장 이미지·품질 낮음 | `CardPool`, `BundleEligibility` | `CardPoolTest`, `BundleEligibilityTest` | 부분 | 스크린샷·저장 이미지 제외 확인 필요 |
| 2순위 중복 | 묶음당 1장 | `CardPool` | `CardPoolTest` | 교체 예정 | R1과 함께 |
| 3순위 멤버 | 업로더 1장 이상, 한 멤버 최대 60% | `CardPicker`(`memberMaxRatio` 0.6) | `CardSelectorTest` | 부분 | 「최대 장 수 − 2명」 규칙 확인 필요 |
| 4순위 구성 | 함께 찍은 순간 1장 이상, 10분 구간 최대 절반, 4등분 | `CardQuarters`(4등분은 있음), 단서·피사체 상한은 AI 기반 | `CardQuartersTest`, `CardSelectorTest` | 교체 예정 | 후속 |
| 5순위 | 시각 간격 10분 → 멤버 많은 순간 → 품질 | `CardScorer`(대표성·다양성·품질 점수) | `CardScorerTest` | 교체 예정 | 후속 |
| 장 수 | 6~15장 6, 16~25장 8, 26장 이상 10 | `CardSelector`(후보가 10장 이하면 전부, 넘으면 10장. 기능명세서를 따름) | `CardSelectorTest` | 교체 예정 | 후속 |
| 커버 | 전면 카메라(D4) → 함께 찍은 순간 → 품질 | 시각 순서의 첫 사진 | `CardOrderTest` | 교체 예정 | 후속. D4 법무 대기 |
| 이름 | 「날짜 + 시간대」 | `BundleNamer`, `BundleNames`(이벤트·활동 이름) | `BundleNamerTest` | 교체 예정 | 후속 |
| 카드 정보(D5) | 장 수·멤버 수·시간 범위 | 기존 흐름의 응답에 장 수·멤버 칩·날짜. 새 저장소 연결(#184 PR)도 같은 필드 | `RecommendationApiIntegrationTest`(기존 흐름) | 부분 | 시간 범위 없음. 기획 확정 대기 |

### 예외 처리 (새 기준 「예외 처리」)

| 항목 | 새 기준 | 현재 구현 | 테스트 | 상태 | 이슈 / 비고 |
| --- | --- | --- | --- | --- | --- |
| X1 | 번들이 없는 하루는 추천 안 함 | `BundleCombiner`, `RecommendedBundleStore`(없는 하루는 지움) | `BundleCombinerTest`, `RecommendedBundleStoreIntegrationTest` | 구현됨 | |
| X2 | 날짜 모름은 계산 제외, 날짜를 빌리지 않음 | `DayPreparer`(날짜가 없으면 `excludedPhotoIds`에 담음. `DateBorrower` 삭제) | `DayPreparerTest`, `BundleCombinerTest` | 구현됨(#220) | 지문 유사도로 날짜를 빌리던 경로 삭제 |
| X3 | 날짜만 있는 사진 붙이기(업로드 묶음 → 번들 하나면 그 번들 → 안 넣음) | `DateOnlyAttacher`(같은 업로드·날짜 묶음의 시각 있는 사진이 가장 많은 번들 → 그날 번들이 하나면 그 번들 → 안 넣음) | `DateOnlyAttacherTest` | 구현됨(#220에서 확인) | 명세 3단계와 같음을 테스트로 확인. 다만 붙은 사진이 번들의 `photoCount`·`uploaderCount`에 들어가는 것은 명세 「장 수 계산에는 끼지 않음」과 다를 수 있어 기획 확인 필요(성립 판단은 붙이기 전에 끝나 추천 조건에는 영향 없음) |
| X4 | 시간대 오프셋으로 통일 | `TimeNormalizer`(오프셋이 없으면 `Asia/Seoul`) | `TimeNormalizerTest` | 부분(#220에서 결정 기록) | 현재 동작 유지. 새 기준은 오프셋이 없으면 업로드 기기의 시간대이나 서버는 기기 시간대를 모른다(앱이 직접 찍은 사진은 현재 오프셋을 보냄, #145). 기획·프론트 확인 필요 |
| X5 | 촬영 시각이 업로드 시각보다 14시간 넘게 뒤면 제외 | `ClockErrorFilter`(업로드 묶음 단위로 제외, 오프셋이 없으면 14시간 여유, 있으면 여유 없음. `ClockCorrector` 삭제) | `ClockErrorFilterTest`, `DayPreparerTest` | 구현됨(#220) | 이미지 유사도로 시계를 맞추던 보정 삭제. 여러 멤버가 올린 사진은 모든 묶음이 틀렸을 때만 제외 |
| X6 | 다음 날 05:00 이후 처음 추천 | `BundleVisibility` | `BundleVisibilityTest` | 구현됨 | 기능명세서는 5분 뒤 반영이라 충돌(아래) |
| X7 | 사진 변경 시 바뀐 하루만 다시 판단 | `RecommendedBundleRecalcTrigger`(D-1과 D, 초안 제외, 5분 디바운스) | `RecommendedBundleRecalculationIntegrationTest` | 구현됨 | |
| X8 | 대량 업로드 노출 제한 | `RecommendedListRules`(최대 20개) | `RecommendedListRulesTest` | 부분 | 정책은 기획 결정 |
| X9 | 동점은 항상 같은 기준 | `PhotoOrder`(시각 → 사진 번호) | `PhotoOrderTest` | 구현됨 | |
| X10 | 한 시각에 몰아 찍어도 규칙대로 | `TogetherMoments`(몰아 찍어도 서로 다른 멤버가 30분 안이면 함께 찍은 순간) | `TogetherMomentsTest` | 구현됨(#222) | |

### 저장·재계산·노출·API

| 항목 | 새 기준 | 현재 구현 | 테스트 | 상태 | 이슈 / 비고 |
| --- | --- | --- | --- | --- | --- |
| 저장·안정 키·재계산 | 하루 단위, 5분 디바운스 | `RecommendedBundleStore`, `RecommendedBundleRecalcTrigger`, `RecommendedBundleScheduler` | `RecommendedBundleStoreIntegrationTest`, `RecommendedBundleSchedulerTest` | 구현됨 | #183 |
| 30일 만료 | 마지막 사진 추가일부터 30일 | `RecommendedBundleExpiration`, `BundleExpiry` | `BundleExpiryTest` | 구현됨 | #183 |
| 목록·상세·적용 | 최근 순 | `RecommendedListRules`. 목록·상세·적용 연결(`RecommendedBundleReader`, `RecommendedBundleApplication`)은 #184 PR에 있고 `dev` 반영 전 | `RecommendedListRulesTest`. 연결 테스트(`RecommendedBundleApiIntegrationTest`)는 #184 PR | 부분 | #184 머지 뒤 갱신. 목록 순서는 기능명세서 4.2.9 규칙 6을 따름 |
| 이벤트·활동 필드 | 없음 | 저장 컬럼 `kind`, `event_type`, `established_by`, `activity`, 응답 선택 필드 | `RecommendedBundleSchemaIntegrationTest` | 교체 예정 | 마이그레이션과 함께 정리. 후속 |

### 케이스 시나리오 (새 기준 「케이스」, V10)

새 기준의 케이스 G1~G25, R1~R5(R4-1 포함), C1~C8, X1~X10은 테스트 시나리오로 옮긴다. 현재 구현의 시나리오 테스트는 이전판의 케이스(E, G, R, C, X)를 기준으로 하며, 새 기준 기준의 시나리오 테스트는 아직 없다.

| 묶음 | 케이스 | 상태 | 이슈 |
| --- | --- | --- | --- |
| 일반 번들 | G1~G25 | 미구현(시나리오 테스트 없음) | 일반 번들 재작성 이슈에서 함께 |
| 반복 활동 | R1~R5 | 미구현 | 같음 |
| 사진 고르기 | C1~C8 | 미구현 | 사진 고르기 이슈에서 함께 |
| 예외 | X1~X10 | 일부(위 표) | 각 이슈에서 함께 |

## 기능명세서(사진)와의 충돌·변경

기능명세서(사진) 4.2.x, 3.2.2와 새 기준이 다른 곳이다. 기획이 기능명세서를 갱신하거나 어느 쪽을 따를지 정해야 한다. 그때까지 새 기준을 따른다(이전판과 같은 원칙, 기획 확인 대기).

| # | 항목 | 기능명세서(사진) | 새 기준 | 영향 |
| --- | --- | --- | --- | --- |
| 1 | 하루 기준 | 사진함 날짜 섹션 기준 + 자정 잇기(22시 이후~03시 이전, 2시간 이내)(4.2.7-1·3) | 05:00~다음 날 05:00, 번들은 하루를 넘지 않음 | 자정 잇기 폐기. 05시 이전 사진이 사진함 날짜와 다른 날 번들에 들어감 |
| 2 | 이틀에 걸친 표기 | 카드 「10월 19일~20일」(4.2.2), 적용 시 첫날 섹션 스크롤(4.2.3), 상세 날짜별 두 섹션(4.2.5) | 이틀에 걸친 번들 없음, 날짜는 시작 시각 기준(D7) | 앱 화면 규칙 삭제 |
| 3 | 나누기 규칙 | 4시간 공백 또는 양쪽 업로더가 안 겹침, 양쪽 3장(4.2.7-2) | 경계 20분 이상에서만 1~5순위 | 결과가 달라짐(예시 4개는 새 규칙에서도 같은 결과) |
| 4 | 추천 조건 | 6장 + 2명(4.2.7-5) | + 함께 찍은 순간 | 추천이 줄어듦 |
| 5 | 카드 장 수 | 연사 정리 후 10장 이하면 전부, 초과면 10장(4.2.8) | 6~15장 6, 16~25장 8, 26장 이상 10 | 14장 묶음이 10장 대 6장 |
| 6 | 중복·연사 | 3초 이내 + 이미지 유사도(경량 비교)(4.2.8-1) | 해시, EXIF 고유 ID·밀리초·기기, 같은 업로더 연사. 이미지 유사도 미사용 | 이미지 지문 사용 중단 |
| 7 | 제목 | 상황별 문장 풀, 15자 이내, 한 번 뽑으면 고정(4.2.2) | 「10월 19일 밤」(날짜 + 시간대) | 초안 기본 제목(4.1.4)도 바뀜 |
| 8 | 카드 정보 | 대표 사진·제목·멤버 닉네임 칩·사진 수·날짜(4.2.2) | 장 수·참여 멤버 수·시간 범위(D5) | 앱 화면 변경 |
| 9 | 시각 없는 사진 | 업로드 묶음 → 업로드 멤버 → 사진 수(4.2.7-4) | 업로드 묶음 → 그날 번들이 하나면 그 번들 → 안 넣음(X3) | 일부 사진이 추천에서 빠짐 |
| 10 | 시각이 하나도 없는 날 | 하루를 하나의 묶음으로 봄(4.2.7 예외) | 시각 없는 사진은 계산 제외(X3) | 경미 |
| 11 | 메타데이터 전송 | 앱이 촬영 날짜·시간만 보내고 위치는 지움(3.2.2-3) | 기기 모델·렌즈·연사 ID·밀리초·고유 ID 추가(F7) | 앱 업로드 방식 변경, L2 법무 |
| 12 | 첫 노출 시점 | 변경 후 5분 디바운스로 바로 반영(4.2.9-2) | 다음 날 05:00 이후(X6) | 당일 올린 사진이 바로 안 보임. 구현은 이미 새 기준 |

일치하는 것: 최종 6~10장, 업로더 2명 이상, AI 미사용(기능명세서 4.2.7 로직 5), 재계산 5분 디바운스·30일 만료·사진 추가일 기준 갱신, 업로드 묶음 ID, 초안에 쓰인 사진 제외, 같은 파일은 한 장·업로더는 모두 셈.

## 미정 항목과 임시 기본값

새 기준이 결정이 필요하다고 적은 항목이다. 확정 전까지 임시 기본값으로 구현하고, 확정되면 이 표와 새 기준을 갱신한다.

| 항목 | 임시 기본값 | 확정 대상 |
| --- | --- | --- |
| D1 원치 않는 날의 추천 | 별도 장치 없이 추천 | 출시 후 재검토 |
| D2 반복 활동 노출 | 규칙은 그대로, 노출 제한 없음(목록 최대 20개만) | 기획 |
| D3·L3 픽셀 선명도 품질 | 현재 구현 유지 | **법무** |
| D4·L2 전면 카메라 커버 | 쓰지 않고 함께 찍은 순간 중 품질 좋은 사진 | **법무** |
| D5 카드 정보 | 장 수·멤버 수·시간 범위를 응답에 추가 | 기획·디자인 |
| D6 여러 날 이어지는 여행 | 하루마다 번들 | 기획 |
| D7 하루 경계 표기 | 번들 날짜를 시작 시각 기준으로 | 기획 |
| D8 시간대 경계 비대칭 | 새 기준 구조 유지, V1 결과로 결정 | 검증 후 |
| D9 같은 날 번들 합치기 | 이후 | 기획 |
| L1 사진 내용 분석 없음 | AI 호출 경로 제거(#198), 이미지 지문 사용 여부 확인 | 기획·법무 |
| L2 메타데이터 고지 | F7 수집 방식과 함께 | **법무** |
| L4 함께 찍은 순간 계산 | 번치 안에서만 계산, 화면에 표시하지 않음, 저장 기간 결정 | 기획·법무 |
| X6 첫 노출 시점 | 다음 날 05:00 이후(구현됨) | 기획 |
| X8 대량 업로드 노출 개수 | 목록 최대 20개 | 기획 |
| 목록 정렬 | 최근 사진이 추가된 순 → 업로더 수 → 사진 수(기능명세서 4.2.9 규칙 6) | 기획(새 기준은 「최근 순」만 적음) |

## 갱신 규칙

- 구현 이슈는 끝날 때 해당 행의 상태·구현·테스트를 갱신하고, 새로 만든 케이스 시나리오 테스트를 연결한다.
- 코드와 새 기준이 달라지는 곳은 [추천 번들 조합 계약](reco-bundle-combination.md)의 「해석한 부분」에 이유를 적는다.
- 이 표의 클래스·테스트 이름은 2026-11-21 기준 코드에서 확인한 것이다. 이름이 바뀌면 같이 고친다.
