# Owner実機評価記録 — Phase 9A〜10C

## 受理した結果

| Phase | Owner報告 | 分類 |
| --- | ---: | --- |
| Phase 9A | 8/8 PASS | Owner実機評価 |
| Phase 9B | 8/8 PASS | Owner実機評価 |
| Phase 9C | 21/21 PASS | Owner実機評価 |
| Phase 10A-R2 | 9/9 PASS | Owner実機評価 |
| Phase 10C | 10/10 PASS | Owner本番実機評価 |
| 合計 | 56/56 PASS | Owner報告の延べ合算 |

Owner判断によりPhase 10A-R2で統計復元P0の修正が確認され、release blockerから解除されました。Phase 10Cでは本番上書き更新、既存データ保持、主要機能の正常動作が10/10 PASSと報告されました。Phase 10Cの個別APK hash、証明書fingerprint、端末情報を含むMac側実測報告は確認できていないため、これらの値は推測で補完・記録しません。

## Phase 10C Owner確認項目

Owner報告の10/10 PASSに含まれる結果は次のとおりです。

| 項目 | Owner結果 |
| --- | --- |
| v2.5.2からの本番上書き更新 | PASS |
| ライブラリ保持 | PASS |
| SAF権限保持 | PASS |
| お気に入り・プレイリスト保持 | PASS |
| 再生統計保持 | PASS |
| 設定保持 | PASS |
| 再生操作 | PASS |
| ミニプレイヤー・動的配色 | PASS |
| バックグラウンド再生 | PASS |
| 再起動後の正常動作 | PASS |

詳細なログ、APK hash、証明書fingerprint、各項目の追加内訳はOwner報告に含まれていないため、本書で推測していません。Owner限定Driveには`FLACtify-v3.0.0-prod-18-signed.apk`がありますが、Mac側実測報告を確認できなかったため、signed APKのSHA-256や証明書情報は記録しません。

## 対象と識別情報

- Phase 9Bの評価対象候補HEAD：`e03dcd9cbd5591e61505387afe9364b7df988dc0`
- Phase 9Bの対象tree：`1190aba1433957b64e98a3ccbc6023d682b2d9eb`
- Owner提示のPhase 9B APK SHA-256：`9f16c65c16906015296de9183524239475df7bdfd423406f82799bab93928f62`
- Phase 10A-R2修正候補：PR #37、HEAD `4592291a08a62dd923509d369df3b224c4b5afb3`、tree `a9c9ed0df88f8831f24dd7b79650accd25c64232`
- Phase 10A-R2対象としてOwnerが申告した成果物：FoundationClosure APK SHA-256 `f3c8a9ce7aa674be1f8a7c5d4d29fcf15b76f0b258910a7d5bf9139560f68a9e`

Phase 9B APK hashは当時の評価対象識別用であり、Phase 10A-R2 APK hashの代用ではありません。Owner評価とCloud CIは別の証拠区分です。

## 評価範囲と未実行試験

Phase 10A-R2のOwner 9/9 PASSは、Owner実機で修正を確認した結果として受理しています。SharedPreferences instrumentationはコンパイル成功のみで、CloudでのAndroid test実行済みとは扱いません。特にrollback失敗、journal retry、異常・競合データ、プロセス再生成、復元と再生/skip並行更新の各自動試験が実行されたという意味ではありません。

既存のFoundationClosure APKとfixtureだけではinstrumentationを実行できません。instrumentation APKとAndroid test runtimeを別途用意する追加build/端末が必要です。本Phase 10Bでは追加build、端末操作、data clearを行いません。

## Gate 3

Gate 3 paused-retentionは未解決です。Ownerの通常の再生・バックグラウンド操作の評価PASSを再接続試験PASSと読み替えません。今回の再接続試験は未承認・未実施であり、paused状態を保持する契約は条件付き残存リスクとして最終リリース判断に残します。
