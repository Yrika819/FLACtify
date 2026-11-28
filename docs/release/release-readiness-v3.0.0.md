# v3.0.0 リリース前確認報告

## 最新更新 — Phase 10A-R1

2026-11-28時点の候補はDraft PR #37（`main` base、`fix/v3.0.0-stats-restore-p0`）です。元の候補PR #36とbranchは保全しています。

- 指定された候補HEAD `4bfebeaa8dd872b0880fdbb85c87ceb18da45440` / tree `50292d79144c27b0cdfc5cdcf7fd83519dbad47e`を基点に、再生統計のP0データ損失を修正しました。
- 現行修正HEAD `96035f9027cc6abc782184d989642ae44442ab12` / tree `cd3e7c85fd77bc3d10f7dbd4db4f7c550c77f931`。独立レビュー（GPT-6 Luna High）はP0/P1の残存ブロッカーなしと判定しました。
- 最終HEADのAndroid validation、CodeQL、LGPL relink、Fresh-extraction ComplianceはPR #37のGitHub checksで確認します。以前のHEADやローカル結果を最終HEADのCI成功として扱いません。
- FoundationClosure APKと合成fixtureをOwner限定Driveへ引き渡し済みです。APKはデバッグ署名・試験用IDで、production APKではありません。
- Gate 3 reconnect paused-retentionは未解決の条件付き残存リスクのままです。Phase 9の結果はGate 3 PASSを意味しません。今回のGate 3実機再接続試験は行っていません。
- PR #37はDraftのままです。mainへの統合、署名、production install、tag、公開は行っていません。

以下はPR #36の初回レビュー時点の履歴記録です。現況と異なる記述は上記のPhase 10A-R1更新で置き換えます。

**当初判断：BLOCKED — production Release APKは未生成**

この候補は、Owner実機評価済みPR #35 HEADからのリリースメタデータ準備です。レビューでHIGHのデータ保全不具合が特定されたため、公式production Releaseの実装継続・ビルド・公開へは進みません。後述のローカル非production検証variantは実行済みですが、公式unsigned production Release APKは未生成です。

## 基点と差分

- Repository：`Yrika819/FLACtify`
- 候補ブランチ：`release/v3.0.0-candidate`
- 評価済み基点HEAD：`e03dcd9cbd5591e61505387afe9364b7df988dc0`
- 評価済み基点tree：`1190aba1433957b64e98a3ccbc6023d682b2d9eb`
- 基点Owner提示APK：FoundationClosure、SHA-256 `9f16c65c16906015296de9183524239475df7bdfd423406f82799bab93928f62`
- 候補の意図した製品設定：`com.flactify`, versionName `3.0.0`, versionCode `18`

評価済みsourceとの差分はdefault versionName/versionCodeとリリース用文書に限定します。製品動作コードは変更しません。候補準備は`release/v3.0.0-candidate`へコミット済みです。正確な最終HEAD/treeはdelivery recordで特定し、本書では自己参照になるため記載しません。評価済みAPKのSHAを候補APKのハッシュとして扱ってはいけません。

versionCode履歴ではv2.5.1が16、Ownerが受理したv2.5.2が17です。候補18は単調増加です。正式リリース前に最終ブランチとversion設定を再確認します。

## 初回レビューで確認したP0：バックアップ復元後の再生統計不整合（PR #37で修正）

`LibraryBackup.kt`は統計を`playCount`、`skipCount`、`lastPlayed`キーでエンコードします（50–56行）。`LibraryManager.restoreBackup()`は同形式を`stats` preferenceへそのまま保存し、成功時に`statsCanBeRewritten = true`とします（165–190行）。一方、通常の`LibraryManager.parseStats()`は`pc`、`sc`、`lp`キーのみ読みます（311–328行）。

そのため、復元直後に通常ロードすると各値が0として読み取られます。読み込まれたentry数はJSONのkey数と一致するため、`getAllStats()`のrewrite guardもtrueになります（130–136行）。次に`recordPlay()`または`recordSkip()`が`saveAllStats()`を呼ぶと、復元値をゼロ相当の値で書き換える可能性があります（201–234行）。これは再生統計の静かなデータ損失にあたります。OwnerのPhase 9評価で報告された別機能のPASSは、この経路を否定するものではありません。

初回レビューの候補HEADでは未修正でした。PR #37では内部永続化codecとバックアップ交換codecを分離し、通常保存・通常復元・merge・rollback・journal retryを内部`pc/sc/lp`形式に統一しました。v2.5.2形式と過去のバックアップ形式を読み取り、欠落・不正・競合行は推測せず書き戻しを止めます。rollbackは元のraw preference値を保持し、再生/skipとrestoreはプロセス内lockで直列化します。SharedPreferences経路を通す回帰試験を追加しました。修正後も正式統合・本番更新は別途判断です。

## データ互換性・更新条件

- v2.5.2本番版は`com.flactify`、versionCode 17。候補versionCode 18はAndroidの更新条件上は増加です。
- v2.5.2 Owner受理済み署名APK SHA-256：`aca14135a9d40a9ac780f24e9792e4e4ac00e7b3a13e6a08e4beb2b7b8d9f3eb`。
- 上書き更新には、同一applicationIdに加えて、既存インストールと一致する本番署名証明書が必要です。今回生成可能なCloud APKは未署名です。本番鍵はCloudに持ち込まず、現時点でインストール可能なRelease APKはありません。
- 通常のin-place updateではアプリデータを保持する想定ですが、バックアップ不具合を解消した後、最終署名APKと既存アプリの更新をOwnerが確認するまでデータ保持を実測済みとは扱いません。SAF grantの維持もOS管理状態として実機確認対象です。
- v3 versionCodeからv2.5.2へ戻すダウングレードは保証しません。失敗時はアプリをアンインストールしたりデータ消去したりせず停止してください。再生統計復元のHIGH blockerが修正・確認されるまでは、復旧目的であってもアプリ内バックアップ復元を推奨・実行しません。旧署名APKを保全しても、AndroidはversionCodeの低いAPKへの通常上書きを拒否する場合があります。

## 既知の制約とリリース上の扱い

| 項目 | 状態・評価 |
| --- | --- |
| Gate 3 reconnect paused-retention | 未解決。既存契約の再接続・paused状態に関する確認が残る。2.5.2を含む既往のOwner受理と新機能評価に、この assertion の解消は含まれていない。過去にOwnerが追加実機サイクルを停止しているため、本準備では再接続試験を再開しない。現有情報では単独の恒久的リリース禁止を裏付ける再現頻度・利用者影響の証拠は足りないため、既知の条件付きリスクとする。ただしOwnerがPaused状態喪失を受容できない場合、または再試験で再現した場合はリリース阻止要因とする。Gate 3 PASSとは記録しない。 |
| M3U相対パス | Import UIは既知のcontent URI中心。任意provider内の相対パス解決はCORE_ONLYで、完全な製品対応ではない。 |
| 外部LRC | 安全性を優先し、文書path・一覧を提供しないproviderでは利用不可へ縮退する。provider間で可用性に差がある。 |
| ReplayGain / EQ | 数値PoCのみ。通常の再生経路へ統合されておらず利用機能ではない。 |
| 製品画面の自動画像試験 | 未確立。Ownerの実機目視結果とCloud試験を混同しない。 |
| Android性能計測 | PSS、jank、frame timeは未測定。 |
| 再生統計バックアップ | 初回レビュー時のP0はPR #37で修正。最終HEADの必須CI完了とOwner実機再検証は別ゲート。 |

## PR包含・統合方針

| PR | Base | Head | 状態・包含関係 |
| --- | --- | --- | --- |
| #29 | `main` | `17ace213810975653fa185561591fbc83258e99e` | Open Draft。#35の祖先に含まれる。 |
| #30 | `integration/gate5-owner-absence` | `a2e1ba148dd7b10a5478fa6c85a5c8f17c4da897` | Open Draft。#35の祖先に含まれる。 |
| #31 | `main` | `b69d1e605b0021cf816cc9330210a8fb73c4382d` | Open Draft。#35の祖先に含まれる。 |
| #32 | `main` | `f114a41e1f37512808081158fc24573177cc7e05` | Open Draft。#35の祖先に含まれる。 |
| #34 | `main` | `cb123655fbe7f62bbdc2b8eb9d6b1c675c90f122` | Open Draft。#35の祖先に含まれる。 |
| #35 | `main` | `e03dcd9cbd5591e61505387afe9364b7df988dc0` | Open Draft。上記の累積候補。 |
| #33 | `phase8/autonomous-development` | `4861c2c72d0da5cf44dd737e69fe74526da74ff3` | Open Draft。#35と独立したReplayGain/EQ研究PoC。 |
| #36 | `main` | `4bfebeaa8dd872b0880fdbb85c87ceb18da45440` | Open Draft。保全対象のv3.0.0 candidate。head変更・closeなし。 |
| #37 | `main` | `96035f9027cc6abc782184d989642ae44442ab12` | Open Draft。Phase 10A-R1統計復元修正を含む累積候補。 |

#35の祖先に含まれるPRの変更は累積されています。古い累積PRを個別にマージすると変更が重複するため、#35を統合候補として扱い、統合前に各headの祖先関係とmainとの差分をGitHub上で再確認します。PR #33は統合せず、独立PoCとして保全します。今は既存PRを個別にマージせず、いずれもクローズしません。

今回、mainへの統合、PRのclose、release tag作成、GitHub Release公開は行っていません。

## Cloud / Owner証拠

- PR #35の評価済みHEAD `e03dcd9`：499 JVM tests、Android validation、CodeQL Java/KotlinおよびC/C++、LGPL relink、Fresh-extraction ComplianceがPASSした記録あり。
- Owner評価：Phase 9A 8/8、Phase 9B 8/8、Phase 9C 21/21 PASS。Owner報告では不具合なし。Phase 9B Owner提示APK SHAは上記基点に記載。
- Owner評価は実機証拠、CIはCloud証拠であり、区別して扱う。
- 候補ブランチのローカルCloud検証は次のコマンドでPASS：`./gradlew test lint assembleDebug assembleReleaseValidation assembleFoundationClosure compileDebugAndroidTestKotlin`。
- この実行では499 JVM tests、Gradle test lifecycle、lint、Debug、ReleaseValidation/R8、FoundationClosure build、およびinstrumentation compileが成功した。Debug、ReleaseValidation、FoundationClosure APKのZIP alignmentとnative ELF 16 KiB alignmentもPASS。
- ReleaseValidationのapplicationIdは`com.flactify.releasevalidation`であり、production IDの`com.flactify` Release APKではない。この検証をproduction Release artifactの検証済み扱いにしない。
- 候補ブランチに対するGitHub candidate PR CI結果はまだない。上記はローカルCloud実行結果であり、GitHub CIやOwner実機結果として報告しない。
- versionCode 18のproduction-ID unsigned Release APK、署名payload、production applicationIdを持つ最終APKの確認は未実施。stats restore blocker解消前のため、公式unsigned production Release APKは**BLOCKED / NOT GENERATED**。

## 独立レビュー観点と現時点の判定

| 項目 | 判定 | 根拠・未実施 |
| --- | --- | --- |
| 2.5.2 in-place update / data preservation | CONDITIONAL / UNVERIFIED for v3 | v2.5.2はOwner報告でPixel 7a in-place update成功。v3.0.0は未署名・未インストールで、Favorites、playlists、stats、settings、SAF grantsの更新保持は確認していない。 |
| SAF grants / library cache compatibility | UNVERIFIED on v3 install | candidateで該当製品コードは変更しない想定だが、version bump candidateを既存インストールへ適用した検証はない。 |
| Tag edit audio safety | Owner evidence exists for recent phases; candidate release install UNVERIFIED | v3 release packageの署名・R8後APKでのOwner操作は未実施。 |
| Backup / restore | BLOCKED | play stats schema mismatchを確認。お気に入り・playlist以外も含むrestore successを一括で安全と主張できない。 |
| MediaSession security | Baseline source review/CI only; no new finding documented here | v3 binary or device command-surface reviewは未実施。 |
| Dependencies / licenses | BASELINE PASS at PR #35 HEAD; candidate GitHub rerun pending | PR #35でLGPL relink、Fresh-extraction Compliance、CodeQLがPASS。candidateブランチのGitHub CIは未実施。 |
| Release/R8/resources | Local candidate ReleaseValidation/R8 PASS; production Release BLOCKED | ReleaseValidation APKは`.releasevalidation` suffix付きでproduction IDではない。versionCode 18のproduction Release APKは未生成。 |
| APK ZIP / native 16 KiB | Local candidate Debug/ReleaseValidation/FoundationClosure PASS; production Release UNVERIFIED | 3 variantでalignmentを確認。production-ID unsigned Release APKは存在しない。 |
| Instrumentation / screenshots / device performance | UNVERIFIED / not measured | Compile is not execution; product-screen screenshot automation, PSS, jank, and frame time are not established. |

## リリース再開条件

1. 再生統計restore schema mismatchを修正し、復元直後・再起動後・再生/skip保存後の値保持を回帰試験する。
2. OwnerがHIGH blockerの修正と残存Gate 3リスクの条件付き受理を判断する。
3. 最終候補から必要なPR CI、Release/R8、CodeQL、LGPL relink、Fresh-extraction Compliance、ZIP/native 16 KiB checksを実行し、applicationId/version/hash/license noticesを検証する。
4. Cloudでunsigned `com.flactify` Release APKをowner-only Driveへ転送し、Ownerがローカル既存鍵で署名して証明書を確認する。鍵や署名はCloudに持ち込まない。
5. 上書き更新、SAF grant、データ保持をOwnerが確認した後に、統合・正式リリースの別途判断を行う。

本書の候補準備は、正式統合・署名・配布・本番インストールを承認しません。

## 次のOwner判断が必要な事項

1. HIGH blockerを修正し、回帰証拠が得られた後にv3.0.0準備を再開してよいか。
2. Gate 3 paused-retentionを、再接続実機試験を再開せず既知の条件付きリスクとして受容するか。受容しない場合はrelease blockerとして維持する。
3. final CI・未署名production Release・owner-only transfer完了後に、Owner鍵での署名と実機更新評価を許可するか。
4. 正式統合およびv3.0.0 release tag/公開について、別途承認するか。本候補準備はこれらを実行しない。
