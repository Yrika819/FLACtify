# FLACtify v3.0.0 最終リリース判定資料

## 判定

**FLACTIFY_V3_0_0_PERSONAL_RELEASE_COMPLETE**

OwnerはPhase 10Dでv3.0.0個人利用向け正式安定版を承認しました。PR #37は通常merge commitでmainへ統合済みです。Owner実機結果はPhase 9A/9B/9C/10A-R2/10Cの延べ56/56 PASSです。Gate 3 paused-retentionは未解決の条件付き残存リスクであり、instrumentation未実行等の制約も維持します。一般公開APK/AABやGitHub Releaseはありません。

## 固定した正式候補

- Repository: `Yrika819/FLACtify`
- 統合PR: #37（通常merge commit、merged）
- branch: `fix/v3.0.0-stats-restore-p0`
- コード成果物のsource HEAD: `4592291a08a62dd923509d369df3b224c4b5afb3`
- source tree: `a9c9ed0df88f8831f24dd7b79650accd25c64232`
- package: `com.flactify`; versionName `3.0.0`; versionCode `18`
- 統合前main HEAD: `f73e8756fb7d26c0a5688419ca51c8c3ff55c9f6`
- PR #37承認HEAD/tree: `6d94f9fc71f18a4ea4e71f4b0a67efced771a613` / `2ab3d24b55986aba17c8abef2c35cf712437dc89`
- merge commit / merge tree (tag前のmain): `6272f8357c4709587e3b793faa5aaad7a7b859fb` / `2ab3d24b55986aba17c8abef2c35cf712437dc89`
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

Owner報告として以下を受理しています。Phase 10Cの項目別結果は[Owner実機評価記録](owner-device-evaluation.md)に記載します。

| 評価 | 結果 | 証拠区分 |
| --- | ---: | --- |
| Phase 9A | 8/8 PASS | Owner実機 |
| Phase 9B | 8/8 PASS | Owner実機 |
| Phase 9C | 21/21 PASS | Owner実機 |
| Phase 10A-R2 | 9/9 PASS | Owner実機 |
| Phase 10C | 10/10 PASS | Owner本番実機 |
| 合計 | 56/56 PASS | Owner報告の延べ合算 |

Phase 10A-R2のOwner再検証でP0修正を確認し、Phase 10Cで本番更新後の統計保持もPASSしたため、P0はrelease blockerではありません。SharedPreferences instrumentationはコンパイル成功のみで実行済みではありません。Ownerの9/9はrollback失敗、journal retry、異常/競合行、並行更新の全自動試験実行を意味しません。

既存成果物を調べたところ、FoundationClosure APKと合成fixtureはありますが、instrumentation APKはありません。全SharedPreferences instrumentationを安全な隔離環境で実行するには追加のinstrumentation buildとAndroid test runtimeが必要です。この作業では追加ビルド・端末操作・データ消去を行わず、コンパイル成功とOwner評価を越える主張はしません。

## 最終CI・独立レビュー

製品ソースHEAD `4592291...`、文書追補HEAD `6d94f9f...`、merge commit `6272f83...`の必要なCIを確認しました。

| 対象 | Check | Run | 結果 |
| --- | --- | --- | --- |
| PR source HEAD `4592291` | Android validation | 46148772358 (#98) | PASS |
| PR source HEAD `4592291` | CodeQL Java/Kotlin・C/C++ | 46148772467 (#99) | PASS |
| PR source HEAD `4592291` | Fresh-extraction Compliance | 46148772334 (#73) | PASS |
| PR source HEAD `4592291` | LGPL relink | 46148772333 (#71) | PASS |
| Docs HEAD `6d94f9f` | Android validation | 46153090183 (#99) | PASS |
| Docs HEAD `6d94f9f` | CodeQL Java/Kotlin・C/C++ | 46153090110 (#100) | PASS |
| Docs HEAD `6d94f9f` | Fresh-extraction Compliance | 46153090127 (#74) | PASS |
| Docs HEAD `6d94f9f` | LGPL relink | 46153090123 (#72) | PASS |
| Merge `6272f83` | Android validation | 46156295182 (#100) | PASS |
| Merge `6272f83` | CodeQL Java/Kotlin・C/C++ | 46156295298 (#101) | PASS |
| Merge `6272f83` | Fresh-extraction Compliance | 46156295167 | PASS |

Merge commit後のmain CIにLGPL relink workflowは含まれませんが、同一ソースの最終PR HEADで成功しています。instrumentation compileはPASSですがSharedPreferences instrumentationの実行結果ではありません。

FoundationClosure bundleのBUILD-INFOはsource HEAD/treeを記録し、bundle/APK hash、package、version、debug証明書を照合しました。unsigned production APKも同一コードHEAD/treeのcheckoutから生成し、package `com.flactify`、3.0.0/18、未署名、ZIP alignmentおよびnative ELF 16 KiB alignmentを照合しています。Owner報告ではこの版を署名して本番上書き更新し、保持・動作を確認しました。Owner限定Drive上に署名済みAPKがありますが、Mac側実測報告のSHA-256/certificate fingerprintは確認できなかったため記録しません。APKにはsource attestationがないため、source対応はbuild checkoutとartifact記録に基づきます。FoundationClosureとproduction APKは同一製品ソースの別variantでhashは異なります。

独立レビュー（GPT-6 Luna High）はsource HEAD `4592291...`および最終文書を確認し、統計修正に残存P0/P1 blockerなしと評価しました。production APKのsource attestationがない点は証拠上の制約として記録しています。

## PR包含関係と統合結果

PR #37を唯一の累積候補として通常merge commit方式で統合しました。merge commit `6272f8357c4709587e3b793faa5aaad7a7b859fb`のtree `2ab3d24b55986aba17c8abef2c35cf712437dc89`は承認済みPR treeと完全一致し、merge後`app/`に製品コード差分はありません。

PR #29、#30、#31、#32、#34、#35、#36のheadはいずれも#37 headの祖先であることを再確認しました。重複個別マージはしていません。7件すべてに#37への包含を記録してcloseし、ブランチは削除していません。PR #30はstacked PRのためGitHub上ではunmergedでclosedですが、そのheadは#37の祖先としてmainに含まれています。

PR #33 ReplayGain/EQは#37と分岐した独立PoCとしてOpenのまま保全し、今回のリリースには含めていません。

## Gate 3およびその他の残存リスク

- **Gate 3 paused-retention:** 未解決の条件付きリスク。Ownerの通常再生・background操作評価はPASSですが、再接続時のpaused-retention成功を意味しません。再接続実機試験は未承認・未実施です。Ownerはv3.0.0個人利用向けrelease承認にあたり、この条件付き残存リスクを受容しました。Gate 3を完了扱いにはせず、今後の再接続評価は別途扱います。
- Owner Phase 10Cは本番上書き更新、既存ライブラリ・SAF権限・favorites/playlists/stats/settingsの保持をPASSと報告しました。Mac側の署名APK測定reportのhash/certificate valuesは確認できていません。
- M3U任意provider内の相対path、外部LRC provider差、性能指標(PSS/jank/frame time)、製品画面自動画像試験に既知の制限があります。
- ReplayGain/EQはPR #33の独立研究PoCで、通常の再生機能ではありません。

## Owner本番署名・上書き更新結果

Ownerは既存Owner限定Driveのunsigned production APKを使って署名し、v2.5.2からの本番上書き更新をPhase 10CでPASSと報告しました。Cloud側での再署名・鍵取得・端末操作は行っていません。以下は将来の署名済み成果物照合用の保全手順です。

1. DriveからAPKを取得し、SHA-256が`f97b866c...bd1b17d`であることを照合します。ファイル名・Drive IDだけで同一性を判断しません。
2. `aapt dump badging app-release-unsigned.apk`で`com.flactify`、versionName `3.0.0`、versionCode `18`を確認します。`apksigner verify`で未署名であることを確認します。
3. `zipalign -c -P 16 4 app-release-unsigned.apk`と16 KiB native ELF verifierを実行します。既に整列済みなので再zipalignしません。
4. Ownerローカルの既存v2.5.2本番keystoreとaliasを使い、ローカルに署名します。password/keyはコマンド引数・Cloud・ログに出しません。例：`apksigner sign --ks <Owner-local-keystore> --ks-key-alias <existing-alias> --out app-release-signed.apk app-release-unsigned.apk`。
5. 署名後のAPKで`apksigner verify --verbose --print-certs app-release-signed.apk`を実行します。証明書SHA-256が既存v2.5.2本番証明書と完全一致することを確認します。一致を確認できない場合は停止します。本資料にはv2.5.2証明書fingerprintの信頼済み値が記録されていないため、署名前にOwnerが保管済みv2.5.2署名APKまたは信頼できるローカル記録からfingerprintを採取・記録してください。候補APKのdebug証明書を基準にしません。署名後APKのSHA-256を別記し、unsigned hashと混同しません。署名後も`aapt dump badging`と`zipalign -c -P 16 4`、ELF verifierを実施します。署名後にAPKを書き換えません。
6. Ownerが通常のライブラリbackupを保全し、署名・package・version・hashの照合完了後にだけin-place updateを判断します。既存アプリをアンインストールせず、アプリデータを消去しません。失敗・証明書不一致・Androidの更新拒否があれば停止し、現状を保全します。
7. 更新後、Ownerはライブラリ、favorites、playlist、統計、設定、SAF grantを確認し、必要な場合はアプリ内backupを復元します。問題発生時はログ/backupと旧APKを保全し、再試行やdowngradeをせずOwner判断へ戻します。versionCode 18から17へのdowngradeは保証されません。

## 配布条件と完了記録

v3.0.0はOwner個人利用向けです。LGPL relinkとFresh-extraction Complianceは成功していますが、公開配布用の対応ソース/compliance asset同梱gateは完了確認されていません。GitHub ReleaseやAPK/AAB一般公開は行いません。Owner限定Driveの共有範囲は変更していません。

Owner Phase 10Cは本番上書き更新後の既存データ保持と主要機能を10/10 PASSと報告しました。Mac側測定reportに署名済みproduction APKのSHA-256/certificate fingerprintがなかったため、signed artifactの値は記録対象外としています。unsigned APKのSHAを署名済みAPKの値として流用していません。

source tag `v3.0.0`の作成はOwnerが承認済みです。PR #38の文書統合とmain上の必須CI成功後に作成し、target SHAを完了報告へ記録します。GitHub ReleaseやAPK/AAB一般公開、本番署名鍵へのアクセス、追加Mac/ADB/実機操作は行いません。
