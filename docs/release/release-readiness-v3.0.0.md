# FLACtify v3.0.0 最終リリース判定資料

## 判定

**READY_FOR_OWNER_RELEASE_APPROVAL — Ownerの最終署名・リリース承認待ち**

再生統計復元P0は修正され、Owner実機評価によりリリース阻止要因から解除されました。PR #37の必須CIと独立レビューは成功しています。Gate 3のpaused-retentionは未解決の条件付き残存リスクです。本書はmainへの統合、署名、本番インストール、タグ作成、公開を承認しません。

## 固定した正式候補

- Repository: `Yrika819/FLACtify`
- 唯一の累積統合候補: Draft PR #37、base `main`
- branch: `fix/v3.0.0-stats-restore-p0`
- コード成果物のsource HEAD: `4592291a08a62dd923509d369df3b224c4b5afb3`
- source tree: `a9c9ed0df88f8831f24dd7b79650accd25c64232`
- package: `com.flactify`; versionName `3.0.0`; versionCode `18`
- `main` HEAD確認値: `f73e8756fb7d26c0a5688419ca51c8c3ff55c9f6`
- FoundationClosure APK SHA-256: `f3c8a9ce7aa674be1f8a7c5d4d29fcf15b76f0b258910a7d5bf9139560f68a9e`
- unsigned production Release APK SHA-256: `f97b866c7e7e7f173e74242affc2a1b09ee244c1b6e676e3e5b9e76ddbd1b17d`
- Owner限定Drive: FoundationClosure bundle [ダウンロード](https://drive.google.com/file/d/1xG3m_koZZm-D2BxG5SPXcX2D1ZQFV319/view?usp=drivesdk)、production APK [ダウンロード](https://drive.google.com/file/d/1rqVuBMdoZRNQ8HTjSlmUV1uDdCjs2MXv/view?usp=drivesdk)。いずれもOwner限定フォルダ内に保存。

両APKは同一コードHEAD/treeから生成され、ハッシュは別々です。FoundationClosureは実機評価用のdebug署名・試験用applicationId、production APKは`com.flactify`の未署名Releaseです。ReleaseValidation APKはproduction APKとして扱いません。リリース文書だけを追加する場合、コードsource HEADは上記のままです。

## 修正概要と互換性

根本原因は、通常の統計永続化が`pc/sc/lp`を使用する一方、バックアップ交換形式が`playCount/skipCount/lastPlayed`を使い、restoreが交換形式をSharedPreferencesへ直書きしていたことです。通常ロードでゼロ扱いされ、次回の再生・skip保存またはrollbackにより有効な統計を失う可能性がありました。

PR #37では内部永続化codecとバックアップ交換codecを分離し、通常保存・読み込み、replace/merge restore、rollback、restore journal retryを内部形式で統一しました。既存v2.5.2の`pc/sc/lp`と既存バックアップの`playCount/skipCount/lastPlayed`を読み取れます。出力バックアップは交換形式を維持します。

誤って内部保存された旧試験形式は、キーが一意で値が有効な場合に限り読み込み時に回復します。本番データの一括移行はありません。不正値、欠落キー、同時存在する競合形式は推測でゼロ化・加算・上書きせず、書き戻しや操作を停止してrawデータを保全します。rollbackは元のraw preferenceを保持します。統計イベントと復元/journal retryはプロセス内lockで直列化し、復元と再生・skipの読み取り変更書き込みが競合して更新を消すリスクを避けます。

お気に入り、通常/スマートplaylist、journal、設定も限定監査しました。今回の具体的な不一致は統計のほか、未知・不正なplaylist行を破棄しうる経路に対する保全ガードでした。設定はライブラリbackupの対象外です。無関係な形式変更や一括移行は行っていません。

## Owner実機評価と自動試験の区別

Owner報告として以下を受理しています（各Phaseの内訳詳細は本判断に含まれていないため補完していません）。

| 評価 | 結果 | 証拠区分 |
| --- | ---: | --- |
| Phase 9A | 8/8 PASS | Owner実機 |
| Phase 9B | 8/8 PASS | Owner実機 |
| Phase 9C | 21/21 PASS | Owner実機 |
| Phase 10A-R2 | 9/9 PASS | Owner実機 |
| 合計 | 46/46 PASS | Owner報告の単純合算 |

Phase 10A-R2のOwner再検証でP0修正を確認したため、P0をrelease blockerから解除します。SharedPreferences instrumentationはコンパイル成功のみで実行済みではありません。Ownerの9/9はrollback失敗、journal retry、異常/競合行、並行更新の全自動試験実行を意味しません。

既存成果物を調べたところ、FoundationClosure APKと合成fixtureはありますが、instrumentation APKはありません。全SharedPreferences instrumentationを安全な隔離環境で実行するには追加のinstrumentation buildとAndroid test runtimeが必要です。この作業では追加ビルド・端末操作・データ消去を行わず、コンパイル成功とOwner評価を越える主張はしません。

## 最終CI・独立レビュー

最終コードHEAD `4592291...`について、次の必須GitHub checksはすべて成功しました。

| Check | Run |
| --- | --- |
| Android validation（JVM tests、Gradle test lifecycle、lint、Debug、ReleaseValidation/R8、APK 16 KiB） | 46148772358 (#98) |
| CodeQL Advanced（Java/Kotlin、C/C++） | 46148772467 (#99) |
| Fresh-extraction Compliance | 46148772334 (#73) |
| LGPL relink | 46148772333 (#71) |

FoundationClosure bundleのBUILD-INFOは上記コードHEAD/treeを記録し、bundle/APK hash、package、version、debug証明書を照合しました。production APKは上記の最終コードHEAD/treeのcheckoutからCI完了後に生成し、package `com.flactify`、3.0.0/18、未署名、ZIP alignmentおよびnative ELF 16 KiB alignmentを照合しました。APKにはsource attestationを埋め込んでいないため、production APKのバイト列とsourceの同等性は独立した第三者attestationで証明したものではなく、ビルド時checkoutとartifact検証記録に基づく対応付けです。両variantは同一ソースの別build variantでhashは一致しません。署名やインストールは行っていません。文書のみの追補commitでは`app/`のソースは変わらず、source mappingは維持されます。

独立レビュー（GPT-6 Luna High）は最終HEAD `4592291...`を確認し、統計修正コードに残存P0/P1ブロッカーなしと評価しました。レビュー対象のコードは`96035f9...`時点のものから変わらず、差分はリリース文書のみです。Reviewerはproduction APKのsource attestationがない点を証拠上の制約として指摘しました。

## PR包含関係と統合計画

PR #37だけを累積統合候補とします。ローカルGit ancestry確認ではPR #29、#30、#31、#32、#34、#35、#36の各headがPR #37 headの祖先です。これらを個別に重複マージしません。#33 ReplayGain/EQ研究は独立PoCとして保全し、累積候補へ含めません。PR #36は指定head `4bfebeaa8dd872b0880fdbb85c87ceb18da45440`とtree `50292d79144c27b0cdfc5cdcf7fd83519dbad47e`を保全しています。

統合はOwnerの別途承認後、PR #37を`main`へ通常のmerge commit方式で統合する案を推奨します。Squash/rebaseは署名対象APKのsource commit対応と検証済み履歴を変えるため使いません。merge commitなら署名済みAPKのpayload/source treeを変えず、統合前HEAD/treeとPR履歴を親commitで追跡できます。統合時は実際のmerge commit/treeと`app/`差分がPR候補と同等であることを記録し、APK binary hashとは区別します。現在はマージしていません。

## Gate 3およびその他の残存リスク

- **Gate 3 paused-retention:** 未解決の条件付きリスク。Ownerの通常再生・background操作評価はPASSですが、再接続時のpaused-retention成功を意味しません。再接続実機試験は未承認・未実施です。Ownerが最終リリース承認時に受容可否を判断してください。
- v3.0.0署名APKでのin-place update、SAF grantsと個人ライブラリデータの保持はまだ実機検証していません。
- M3U任意provider内の相対path、外部LRC provider差、性能指標(PSS/jank/frame time)、製品画面自動画像試験に既知の制限があります。
- ReplayGain/EQはPR #33の独立研究PoCで、通常の再生機能ではありません。

## Owner本番署名・上書き更新手順

既存Owner限定Driveのunsigned production APKを使い、Macで再ビルドしません。本番署名鍵をCloudへ転送しません。以下はOwnerローカルで行う未実行の手順です。

1. DriveからAPKを取得し、SHA-256が`f97b866c...bd1b17d`であることを照合します。ファイル名・Drive IDだけで同一性を判断しません。
2. `aapt dump badging app-release-unsigned.apk`で`com.flactify`、versionName `3.0.0`、versionCode `18`を確認します。`apksigner verify`で未署名であることを確認します。
3. `zipalign -c -P 16 4 app-release-unsigned.apk`と16 KiB native ELF verifierを実行します。既に整列済みなので再zipalignしません。
4. Ownerローカルの既存v2.5.2本番keystoreとaliasを使い、ローカルに署名します。password/keyはコマンド引数・Cloud・ログに出しません。例：`apksigner sign --ks <Owner-local-keystore> --ks-key-alias <existing-alias> --out app-release-signed.apk app-release-unsigned.apk`。
5. 署名後のAPKで`apksigner verify --verbose --print-certs app-release-signed.apk`を実行します。証明書SHA-256が既存v2.5.2本番証明書と完全一致することを確認します。一致を確認できない場合は停止します。本資料にはv2.5.2証明書fingerprintの信頼済み値が記録されていないため、署名前にOwnerが保管済みv2.5.2署名APKまたは信頼できるローカル記録からfingerprintを採取・記録してください。候補APKのdebug証明書を基準にしません。署名後APKのSHA-256を別記し、unsigned hashと混同しません。署名後も`aapt dump badging`と`zipalign -c -P 16 4`、ELF verifierを実施します。署名後にAPKを書き換えません。
6. Ownerが通常のライブラリbackupを保全し、署名・package・version・hashの照合完了後にだけin-place updateを判断します。既存アプリをアンインストールせず、アプリデータを消去しません。失敗・証明書不一致・Androidの更新拒否があれば停止し、現状を保全します。
7. 更新後、Ownerはライブラリ、favorites、playlist、統計、設定、SAF grantを確認し、必要な場合はアプリ内backupを復元します。問題発生時はログ/backupと旧APKを保全し、再試行やdowngradeをせずOwner判断へ戻します。versionCode 18から17へのdowngradeは保証されません。

## 配布条件と禁止された操作

`com.flactify`の署名済みAPKは既存本番鍵の所有者がローカルでのみ作成します。現候補は未署名かつOwner限定配布です。v2.5.2と同様にOwner個人利用の範囲に留めます。LGPL relinkとFresh-extraction Complianceは候補CIでPASSしていますが、公開配布用の対応ソース・compliance asset同梱ゲートはこの候補の公開用Release locationに対して完了確認されていません。したがって一般公開の承認・準備完了とは記録しません。署名・本番インストール・マージ・タグ・GitHub Release公開はOwnerの別途承認まで保留です。

本書の作成時点でmainへのmerge、既存PRのclose、署名、本番更新、Mac/ADB/実機操作、個人データ取得、tag、GitHub Release、一般公開は行っていません。
