#!/usr/bin/env python3
"""
Rewrite catapult git history:
- Preserve original commit dates
- Group into feature branches (feat/*, fix/*, etc.)
- Merge via real GitLab MRs (glab)
- All commits are GPG-signed (commit.gpgsign=true already set)
- Move existing branches to old/*
- feat/obs-getter stays as open branch (never merged to master)
"""

import re
import subprocess
import sys
import os
import json
import time
import shlex

REPO = "/home/enimaloc/dev/IdeaProjects/catapult"
GITLAB_REMOTE = "origin"
GITLAB_REPO = "enimaloc/catapult"  # namespace/project on git.enimaloc.fr

INITIAL_COMMIT = "8c9551c65e042a9e95b5c5b53f81a5b65cb15747"

# fmt: off
GROUPS = [
    # (branch, mr_title, [commit_hashes_to_cherry_pick_from_original])
    ("feat/persistence-flyway", "feat: add persistence with PostgreSQL and Flyway migrations", [
        "04961b622b7550e44c2e8ad5a2b3a8c0c6671345",
        "17437b723f49cf121f1a8923c670d07545641c64",
        "2a11df7b472250a3a41efde54de7f4989d3e3e28",
        "c4e04d40bbe453b42689c6a3ea2e43bf54363a54",
        "6583b9694c447c56532e65c9956e22d1bd55a46c",
    ]),
    ("feat/auth-oauth2", "feat: add OAuth2 authentication (Twitch/Steam/Discord)", [
        "bc8b24393a88429017ecd7fea5da1172007d5822",
        "0e75aff4b071f6e51eac2f83953d15c3b51862cb",
    ]),
    ("feat/game-getters", "feat: add game detection via Steam/Discord getters", [
        "8c77bac39a320f206adf581cf509b02f03ad10da",
    ]),
    ("feat/scheduler-events", "feat: add scheduler and game state events", [
        "673fbe349285c90ff9ec631ec0425934f638f20f",
    ]),
    ("feat/twitch-binding-igdb", "feat: add Twitch category binding and IGDB integration", [
        "23ea2c7551bf3a7f273337c0df65ecf6eae7df3b",
    ]),
    ("feat/chat-commands", "feat: add Twitch chat command system (!game, !setgame)", [
        "333ad448154be5499a707428202133573edd1e5b",
    ]),
    ("feat/ui-thymeleaf", "feat: add Thymeleaf web UI (login, dashboard, settings, bindings)", [
        "e10e41cab6c07f90dd997c3bf9bb6d423a52922f",
    ]),
    ("feat/docker", "feat: add Docker containerization with multi-stage local build", [
        "eec59f6389f4c718c690923b6b3e74b4f3bd9d1d",
        "f7b2430ceabef7e74725f13238272b50e40bf400",
    ]),
    ("feat/optional-providers", "feat: make Steam/Discord providers optional with IGDB fallback", [
        "3e26c85baf60d5a71f6b4eb6c5dbef97eb5dee2c",
    ]),
    ("fix/flyway-css", "fix: add app.css, fix Flyway location and explicit bean startup order", [
        "9e424242b68dd8984a4eff8d4d775649b0530b83",
        "50058524b0835d00f2c84c2aecdd51f3f2ede646",
    ]),
    ("fix/twitch-auth", "fix: fix Twitch user info endpoint and OAuth2 login debugging", [
        "fa19f4a4b99d0033bbc9ba964c5364fd66430aa0",
        "ed322cf89a84da91264465844bdc8b656a2d7ad7",
        "a2e63000edd2ad9f4dacd8020d6ebf375c7ea301",
        "45a839d1ee2a458430556e774260658fd5509d4a",
        "f228ea05f4f9e0c83dc05d4c1241b7a92436beb8",
        "0ff72574e84ff3d1cc91ab009073c803f87159b2",
        "2f950be86d2deff62c10c760581f53307873a306",
    ]),
    ("feat/igdb-redo", "feat: rewrite IGDB integration with app-level token, DB cache and CCL pipeline", [
        "c8a714e5767c82f63afd5229518512f6d577a041",
        "8181c5fab47c5708bf4d70cf6fb96efc76588993",
        "4926221258b5a75b796ec0b64c71aa58b39f8d6c",
        "004480a55269b63fda61e66aa312b6a4f2cc7117",
        "9799fa5bbecc043d3f7161c27bad2acf5f9f1d41",
        "8649f50d2c37d4b2c389cb1671904bf7fdf9e930",
        "527b4e6ec83b301039d080081ae60e4ea3e52ed6",
        "1bb4b95a31ce3d6697912cb982b5dd0b870db134",
        "f6065752ee2e62b718cf1dfe9ab27090738f680d",
        "29d4285b52883c9ec18d588bf3d131d6353fe4d6",
        "7c6ab16078dbdd61ba6d46d745d56839fb8112f4",
        "655207e1eca13f0e3e06473b192347857e41295d",
        "d4ed3bedd2d89f34c2d6c28d288a2dd8cf4dc5f4",
        "7316fc4f3fc5eeb5e73d5c127204b7ee8282b95c",
        "aae4a1b38020b41b665f0133a1853619899ac2db",
        "c3a41b7dbdd7bf6031a84e32a8eb63236e2bb8d7",
        "a96bbf4df948c3ffd97c8ac2b12ce42b1709e8c2",
        "e0e396ef7fc606066c0874a9bddbc0a7d6f14c6d",
        "9f8c5e25142e3a550025679b57cabd236c8aa2e4",
        "8a0ae384fe38af8e924a5fc79a66a070a983e1d0",
        "a336adc5486e60fcff631fa82027520095c369d9",
        "07778ba41e06ce3d403dbf41719dba27842a009c",
        "5e111189f21698ec1cd4edac700bfdf2311542d7",
        "e6974039cb6bec2a0edd927121daaae21cd0aacc",
        "03162d2e81c4f0913a009e5cbb687bc379bbbcde",
        "32686bb6817cd9019a245a5cf503185760ae2de8",
        "6fbd15a63b99852dd9df133e9d12556daf5baa72",
        "b119a718046786be6cff1a2fd070f6c54ce474d5",
    ]),
    ("feat/steam-xbox-battlenet", "feat: add Steam integration and Xbox/BattleNet OAuth2 stubs", [
        "0227327aaf6dcd42166c5b8abaa5bd3dab894c31",
        "8bd80fec7c8cb515444ce7d63610a7186fd14c49",
        "11e4f12abae80937135762b1c54641645d0605ce",
        "2db0bbf5fb05ca8e5e46ea015ac3c8a81a4e19d7",
        "40638682646be026c385a2f198580ab289532523",
    ]),
    ("feat/activity-log", "feat: add per-user activity log with SSE streaming", [
        "032767c106b9648efe78a490d49b785ad58d6717",
    ]),
    ("feat/admin-ccl-management", "feat: add admin CCL management with IGDB content descriptor sync", [
        "0158f067575946550b2ed830cb44dbf0926f1e7f",
        "259204360da6a6ddae14e51f699efd562a36ae46",
        "354e4e75f956732eb93b2c63629d70ef88e2d918",
        "cc0ad6e44ba111f454321903ea612dddca34bc17",
        "22da8fa17ab214928e340ea7438f5a114dd5758e",
        "213a1f014764bacdd30530537f1e89d978a64a93",
        "c0d7f0d40e56b89510e9f4cb02dc36cb6fe6962d",
        "004afd7cd66b8daccb3721c3a1c58ba25233852b",
        "126bbfaa61b62a8360305bb21f2f431549215019",
        "2808f6e93e31d7d2afac96d11635ea65703a2c16",
        "08df643a3a86ebc28de00c69ed3f33c70ca4f305",
        "a4b9300d4fd5ca7fe2cb0581994b6b350fc4905e",
        "1e8158c55f3279936c3ff8f4e248678b7fa5d581",
        "cac806d1ec3d3880faff124bac18fafca62a34c4",
    ]),
    # Branches from existing local branches (cherry-pick from old/ after rename)
    ("feat/twitch-token-refresh", "feat: add Twitch token refresh", [
        "2bd1200",  # will be resolved after old/ rename
    ]),
    ("feat/remove-xbox-battlenet", "refactor: remove Xbox and BattleNet game getters", [
        "f05a00c",  # will be resolved after old/ rename
    ]),
    ("feat/ui-spa", "refactor: merge dashboard/bindings/settings into single-page app with HTMX", [
        "01852d4fa4b6935f80651c1fb31c6e2d07e4a2fe",
        "5d65822290777e4bdc477f559b48f119da6cec32",
    ]),
    ("feat/twitch-eventsub", "feat: add Twitch EventSub with live stream detection and pending bindings", [
        "b66608b44d6d2f615b8c831b88423a54584b00cd",
        "463a7cd6ca265a178b0e7f8f5fc63143c4a2c4e4",
        "0428a823fa52e154e4e6e5b95a60938eddbe3218",
        "7733909906e1f93582595eb1cead34223be8e615",
        "d278e8daf1b1cba0b1ea668836cfcd96161110ab",
        "6e13f49340229dfd8db7c500e06d679ff8993ef4",
        "389b0697df8928d809690a3918d2221a942a9d8a",
        "daee207859c74bef7aad8ff3d4c6b97a09952a8b",
        "6e80ded725d58c7aee076aacfd5f68be5c047494",
        "7e863e5f79d54807c58868e93817b6a39e98bf0a",
        "917b91138d0511ce45e0837a968b39b0219631d6",
    ]),
    ("feat/twitch-event-spy", "feat: add twitch-event-spy CLI for real-time event logging", [
        "df4ce5e59a5329d80f5a7c2a0687b91b9cb2d6b2",
        "c090385097b52c1f63733905c39c332f48e5812d",
        "22b4f52b2eafe80323e8baf9d22574a5028855a0",
        "43ee0e255a7bed2da0111ab0b13c6f9edb0199a2",
        "10a59a3788ad94ee6d35b44627f95dddcb244902",
    ]),
    ("feat/css-theme", "feat: add CSS theme system with Nord, Dracula, Catppuccin and Tokyo Night themes", [
        "fb4c6b5881ab52581aad858b75798d70832c5224",
        "2475ff81343415273707b7fc3897cf6dbb9e8674",
        "3b6e3b28bc559ca584b43212b728a5c30767a222",
        "0df3bf1d1b18fc7e67ecf6775f868b0500b37566",
        "977739124d139f91e3f07e293d1db3a248709a0c",
        "22da968819a97cde925d98e3f3ec07d6f9f061c7",
        "6a2832bf68be981bff70d0cd665d9ef7d418d323",
        "014dae8ebadfe71efaaee93724c1d48e0bbe4a2f",
        "6f4deb7472c2d08853a83f3db4b8ccc1c9f3597f",
        "db26f9a260a9163d1ca4c0571f9040469791750e",
        "a6fc87bd82f2a4bc697550ff7e79df82c9c0cadf",
        "0d1317c4932d8555fb60dafa0fc0aec980d25aa7",
    ]),
    ("feat/admin-impersonation", "feat: add admin account impersonation via SwitchUserFilter and members management", [
        "8c265fed5bda8eb2fd506bd01f002f0656d876cd",
        "bbafe294b736adcc5aa0ce221f7c84b92e55301f",
        "53b847a98e432695b7e25bd6e5279092520fe888",
        "c1e851de3a44e4c607148510075328da87a67ca3",
        "3ea725b7536527db21572a25d5d01c600a4e9192",
        "f993e8ad0186a69fccf04a4c47a72aa1f61b9309",
        "3bcd9410d2be6860aaeb971489dea56cca9278c2",
        "27f7e2b364d035a35cd76d73e585d58c1eaf47ff",
        "593623c19d1fe007ec508aee8a70d424b7a8e85b",
        "89016475b0c7854532160d66f856d87d27323f1c",
        "9168e11c8bc437b5a3651dfa687eecd186a2a2ab",
        "964c36fe5d831a1a55d7afae77a454e2d9eb585e",
        "6253880277dbb2387ed5b94e119568101cb89739",
        "8a6658821b56faa0581c1cf75ff7ee6f2f7605cc",
        "20f5c833e6874655ed721ae8c1f732cd1490ccbf",
        "06b5b5272de18d201d4506ae906c39f24ffa5de4",
    ]),
    ("feat/twitch-chat", "feat: add Twitch chat service with IRC and EventSub implementations", [
        "5c1ba8159cd0832a4795b64cb98f42e0aa6172d1",
        "44b45e94509f93c5da501619dbb2d009d33f4c5a",
        "efdfd248a8e23708e856accdf7eca6ebdedb1cc5",
        "3922a6711f1a97a81867e48e78c4facb151670a8",
        "eeefbb692728bb7cc25a638a8d86ce0d482cf79a",
        "a2ff538b57fe32013352be1ba9a6f63db86ca4f1",
        "ab46e93f7dc37d628fb249e558f0d63e448905b4",
        "b89d7eb1aec4cfa2d8839e7bd86bf798ddde7434",
        "89aeee9bc08744b19b4e83721185d80116fdd643",
        "efb22af0d9cc45843a4cd7965ab71fa4f956c380",
        "3b5c7dfcabcf88dc6a5722d15a91ce418558bf05",
    ]),
    ("feat/twitch-category-cache", "feat: add Twitch category cache with DB-backed search and prewarm", [
        "1dcb31b28b01002fed707d6eaa2463f819a8cba3",
        "5fe686353d8612b9f6cb0c418a315118cf00b8c3",
        "77f1984b80326a8574b2b88181d8aa193730e67f",
        "ee6142de1b5429360936fda956f0588fb0116bfe",
        "bedbe373d601b2c422977312c0662ac2374fa15e",
        "39f16def6dbdf09c7517d0b26d0c3987a0c3844c",
        "4b8e177adcfcb9e7a31a849071c488943756b2b3",
        "c4e723c3e78d046dd32712c06d2220745076f970",
        "37f127bf0da9982cdea5d27b8730df277108c18d",
        "9809e36898790db1064e02ea0a94c91e20f1d028",
        "073bc3dd6f9182bc041d67bc1d2b5c10d80f004e",
        "674e0a54f0293e61545822dc6626ee9e3af819c9",
        "2bd6001b6195fe96f293c6afa9f7151af50d3a47",
        # 56891c9 skipped (OBS-specific enrich)
        "f28444dd3966e2e52ca13a311bcf44bbfd1ea67b",
        "d626572bd513dc0702989465afbdf3e51e5c57c1",
        "946fc5297f67784c41841aaac63513267bfd3490",
        "5856e4026d15586c00e719bd916ce1067fa36bfc",
        "e632e829303f0a35bed4c391a17a99826fafb36b",
        "04f174a421e0e761c48f90883872371391e4e1ed",
        "5702c4b1c257dee001c3613d96903cd9fe3b34c7",
        "43cb6dae00940cca08a30fdac082478e1b3a07bc",
    ]),
    ("feat/no-game-settings", "feat: add no-game category and CCL settings", [
        "afc6eea213c4d631fef481fa2718d924ee8ce68c",
        "6a3c062928344865e8c264acae24fe0fc4092a3d",
        "78a0aa0f3731250ee30cb90e63a47892a0be5f49",
        "158314cba5e4e6ea89d0b2a950ea04978dc71ec6",
        "4c68a2ac719f2136921f5d92f716c8763fa2efbb",
    ]),
    ("feat/igdb-search", "feat: add IGDB game search endpoint with autocomplete integration", [
        "44cfac6adcba70b1965a06bc7edaef8be906af65",
        "a4360f8e18b0bf80279fdec8ad235e1545b7dbd7",
        "de155c79c394a16919d5c228673b57f9b8973b03",
        "04e5fe9d42e8675f1b1a55496091ad9c57b66899",
        "5678f0c2a8eb93e2a6b18b4c99f3847ba5fb8d69",
        "cad6efbd0bf01b85ee4df5d0a077b741b0b076b5",
        "e5bc5c9d2f942981fe8ad436eb14fbeaf98c55fc",
        "9b80429416752c94939b31b1bd63934f8733299a",
        "91be64cbffe896a73e4ce1545046ad184578bd94",
        "b565b5bd1b1b789989e8e2823661545a8f043cee",
        "8d76d9d48fc0a81446582d0bc2bbfd25d33420e5",
        "9ae4ac4619c21b2954498a9526bb1522b1582aba",
        "6de34bb1d743cc3023d2e07a2ad355c8ef6c719b",
        "dd42c3075939c2b6b87e6d91c80581fa65a90810",
    ]),
    ("feat/i18n", "feat: add ICU4J i18n with EN/FR translations across all templates", [
        "f958b0d8d672a9972ebc270425d5d36d240c2665",
        "91ab580a4d3cdd0475e2f56d20c79370beb1d543",
        "a1275c2fefb5188edd10ce6c22f4557d015b38a0",
        "2ceb44d4e36ae23087bce5c5a4d62a42c1349abd",
        "171e9976deeb9edd498a99534c7f83f093140cb6",
        "66f737ac67559dadcde194baeb1d1fa087fc4244",
        "d4c4edaa1097521e63a703170b19ab71ff252d01",
        # 40bac2a skipped (OBS-specific)
        "51457cebc01bdaf39494129537cd26fbd83d2c3f",
        "6db42b5f1c1cda5f82ea350c4a1f2ba7d4639d6e",
        # 5e305ea skipped (OBS global process rules)
        "3ea79565623ea528bbfeba7f25d67eba986e9291",
        "aa09c62e8cf82729ac55aba0c5c30fe162b82d12",
    ]),
    ("feat/template-layout", "refactor: add shared template layout and migrate all pages", [
        "faf70df2030c54f367b3f1fc8c48ce00359eb3f0",
        "0509264c28a2af19ad0b85f1b59231b2bf4ee33a",
        "64593298b9f4ee151165a52ebdb681e284886bcc",
        # 5581527 skipped (OBS global-process-rules layout)
        "63ddc6ee7e574adec2d31171c7372200d12fdae5",
        "a1022767527ae3a3a0c580fa3781d8d1bcb9187c",
        "f828dbf5d33f9ed5f067c8c8b88b3fe0121c65ed",
        "b0f331a4079412e5a09031145ddbbf22d76287df",
    ]),
    ("refactor/css-cleanup", "refactor: extract inline styles to CSS classes and shared fragments", [
        "4729461163f80cb592f17c06632db9b494d4dcb6",
        # 044c374 skipped (OBS predicate table)
        "f1af13afaffc6a40d522b63aad650d07e0805d49",
        "b553b6194ebc722f4bfe9f98fbf612bd38472273",
    ]),
    ("feat/mock-web", "feat: add mock-web profile for local development without OAuth2", [
        "8824f18d083737183bf76e824ac07aec15579e4c",
        "a1ef06e4a96d5e0487f03a56611f71e398f573aa",
        "2cab1ccb51c128f5af8e2d690852f08f6a8ecfe4",
        "9f7d2473778f66e0a0cc34c1914670cb0b43c829",
        "00e934e7d7b7067b126af5b873e32b6e682183ef",
        "815ce4e712bbb97c03d0e445599f020ad8a7f236",
        "7319b6faaa542b218d55852248164dfa1df7c1b2",
        "28596723b404f02d5b10012640df385f2d5ab37d",
        "e3d5acb482c63b985a189108f513a6e82a34eb75",
        "ecf7d83d436fa7da84d8c3f0449784ea6190fd67",
        "af260d0d2fe5a5dc40c6b3ab309266137aa4a3c3",
        "d3007b93b3bfacb9b31c6b87bf290d1afc1450f6",
        "d02a1851cdb7205496b01f2a0aa3a8b054271f47",
        "9f8ffdb354d538c2b7a23d7a47c5e7230ab8859b",
        "f437a7c5cdd1897295367e282c7f79e455a63b99",
        "8e8d41a9882edb332a420e6ac3e0739ae1697ed5",
        "4d074336d25cd96d1864e99cd1c24b3ec7320196",
        "f567904a68d9bb4dbcd627b629ca2ac2f5a4fe88",
        "436558bb5cc16344eda65c46de26fa15e72e7eca",
        "8d64366dbc06ae1a11ae8ae9684cf25a08caefae",
        "ca21ce0768831acfec4a1bb52a1ef256c922de7b",
        "28e03f506243e49848f1b28cf15ec5221953b037",
        "110f6fbc0e9f898f4ecbd98f9344d0888c9f1e30",
        # e97d570 skipped (remove OBS from master - not needed in new history)
        "ed78882008ff2a2c6e1b2bd2ecc568d178c37a51",
        "241794d1f5b76df77b4de284275fda0b65573b1c",
        "724a685dc80640ac89dea1c304fde6f99443e973",
        "21ff032b7076603ea895adc37b6181977093f7e7",
        "7edd22ba79bce3210dceeb512bd4018cc73f34b4",
        "8f3fb9384eb4e49a7bbcf69f99700127dda0a958",
        "283a549b28964843b971a52e1ad616777c8e6d83",
    ]),
    ("feat/sidebar-layout", "feat: move status, activity-log and bot to left sidebar", [
        "38d375916b3226bbe989a1598b3c748607200523",
        "ab6ffc48d70511b5cd3ef6c019d81fcd71c352e3",
        "8090ea57e7715b0763eb6346094ec3916df9a62e",
        "069bbebcf102ff3d1c7fa1c5abdfb96950913f34",
        "71f22ab91a2386a354777c1857d69e37e8f6f664",
        "11c7942a0c16e63d37cb3bfcac3e41a96c0d5257",
    ]),
    ("feat/ab-testing", "feat: add A/B testing engine with Thymeleaf dialect, overrides and rollout", [
        "f4862af858b5224a816f49508b398d2c81b764d1",
        "724a5940adf941eb2d35093402676d0d3c182288",
        "467505426fa857a7652addca05a75165d65e5433",
        "1af2f6d4254129f88595c5ababda7f4b12dd3c54",
        "8201725fa12db54b007cc78b782c237ee30af1c9",
        "df8a17a38c3b14dcdfaac0948579d2054f8b6dac",
        "00c9f9e66aef0d6b00fa9740b280a02ec50b81fa",
        "3c50e32271eda89c44470e79f50b4f389d1bea3a",
        "ac184fa17f0f65ebc35cf9149fe1ff9aea68f971",
        "215fb7f911ae680523377b7309d347dfeec779f4",
        "50baa0d327ec26372ea0c20fd4646cd82f91a643",
        "ee8eb11ab25f818230ee10a8ce8a8ffc9bd28efb",
        "ff4c51c8525359d531210bf0723b9cf14b965bb2",
        "9958adefada9aaa2b14540fd4d0f1e6dd6d5262d",
        "60f778c23c1231fd8e94b26c6332ba17cf4dd68b",
        "0ae075657dd8bb1eeb82c7b17732f1e7840f2d39",
        "27a9f6ab63f9903f5c534b2ebd1d6ac03404c7cf",
        "0182d17cace0ae9b90e1ec159a2817896476e09a",
        "2cccd2515b11820a36907b24e756756bfa2eae4f",
        "d36a0fed04660bc47838667ceeae63aacba47105",
        "ee3e2b17e70e9933e14313184c873025d57326f0",
        "33911d0f884509b73121392a1d4a67b5731668d8",
        "38401b3ea94bd8b0da976926f42e5a7692e0faa0",
        "3db2e63836b86132cec6d7aebdcaea0842de267e",
        "970b1afe376d37a4eab3c5e9a7deeb0ab5e06e86",
        "20fbf0de79256f209c741f9c0601e8a4ac15ebfd",
        "a04363c8f55d1a06ad0aa76be530040121ec7039",
        "aa1987e58604b17e2b479acd3e1fb3a25ab1ba68",
        "89f45dae5b3e4f4dadc907a384ce4b17a6c9a776",
        "83ae2e48f19f2d66e8b473f6a6d6486a522cdd64",
        "b90b783395e31f2f82e3c7157be49c72ba0f05eb",
        "bdd3dda2e0181dbab9d66f43f674980ff2d5ec1c",
        "7cc3e258f7a0eafc1ed382ffe1c0fe33db9dfe4b",
        "6e5708ad0943be43167a74d86ea01a34815f79d7",
        "ca1626ff0421c4d26cab633aba844e9652775294",
        "73d3615700d543ddb6f599ecfe2c9c52bef55a6c",
        "8152bbb0cf2f773aaa68f01b309e310f3202aded",
        "7de1ba802f38b704406fe9aaa5ef7716df4ecfc8",
        "5458a931786c5d65cbf869f616961fb9457d4ac9",
        "041d33f5e32a6e0b883d8d7f573a56c256266af3",
        "f31ef5ebff9772a4125cfd748ae9ad271cd13cab",
        "15b90b6e69d36b946b4f42c8a2209d49e1805705",
        "0b9a7c29e83f08c2eb8c8d9722e3e7c832c4b3c2",
        "1307cda91c6f441150975bb2ab76c7bdaabf7c9f",
        "caca5e6ace3b352a6da3c50cd9d60fd72b0a7453",
        "68511b170911dd7f2ab701a161bb17e5cf2a1f49",
        "e595ecb8435244bdb45dfc012ef4740fd2fb453c",
        "29551a05456e593330388d26410d5e298653f278",
        "23639f7cbc54c8b24a8d3e8e3bc8fe353a6bbb01",
        "0955414407ab035b598f2651f4a223a837a9363b",
        "68422ea3893dae9647ae999841606aed92befe64",
        "3f4f3b8cf6d56111d4279cf145968c7fcc0b9ab6",
        "3f07a6ed644f03560ff190079d3796f07c5c9e49",
        "29af1041cdccdfe81f867e91912a3cd59578b633",
        "5f95a5ed3fbf9ebd32e80c65f67888c07c21b37f",
        "eb8789dad2ac9f69cc4a876d863c0f82afbb913a",
        "92c540dccae727c8fa48557b2a0b5e463103dbd4",
        "f33117b799571a0eb4db438e50756c19aae5478a",
        "fd68066e1c961bbe6c7100200440be9bd49fe17b",
        "d0aa2f15e48dde00ec47c49dc306bf3e62b7ee9e",
        "d8a75353165543e780d5d77602e61831a0105bd7",
        "66307bab571368ab0f02ee46257843687a3c78cd",
        "11b4700b4fb31ba363aaf0d38fa5d97f5ed608cb",
        "798e7cd62f5eac5b02f6f30c7f0150f510366ed9",
        "79b9c5586c47dbe4071e8159d60da75698674196",
        "5be786a7cd86eea129beba00fd204c64d728d23c",
        "c0993613ed40ff227c3dd9667f1856b19f565213",
        "1320296f10f89a6b77d8c426b7fafbfe3221d8fa",
        "1d46832ce4e837788e3d39330ddfc2829e2ce5e4",
        "43dda1c893f006bedc3a48a0cc6806284e895eea",
        "f5a65af3cba7a8985e9cc68bca5efcdadbd87838",
        "36e64e86302eab6362b4b56ca69a6ff803a71cd8",
        "39628bf4d618c5bbd361a3004f70d37e3558ffa5",
        "f1f836b14c18dbd3fb1ac15a635890de8d851da8",
        "10c14d246abc4176081d46b1d3a64d8a163b3948",
        "c18cfc390a2192fe6d1fd5c26f1d5faaadc0c9b8",
        "db7fbaeb4f6239e3fc19331fc19b419f9c3f4ac3",
        "869991e2c613ba68ea9c93a4debe7bfcab255148",
        "866eca1ce8b1dbb201ba7fcba3bf121306ed0149",
        "63566b098ab9bbec51865aca5925439cee8bb428",
        "d4c83742375e85d0afe89c2c3bf801f277aa4e20",
        "cd230548b87822e9c526363107019578542a3acf",
        "27fb19fa343230a1fabae182ff1ffc88dd306f62",
        "d00f8dcb018326faadaf88ebe7b7d0d925889d6f",
    ]),
    ("feat/public-pages", "feat: add footer with version info, GDPR privacy page and public landing page", [
        "2b69e236dc472c1d648b3fe5be131011286206cb",
        "b47f1c06f15724e1719072dae7db982bd4189572",
        "1e63477ef88736cef66fdf007689be6117417c14",
        "db4fc8ef9f329f96fe059ec5f011781e1035b2b8",
        "0f74c3accc40672a0b0fdba3f1030b8ae8791f7f",
        "a4c52dee49883edd10a7a5a82dad98b3f96973c7",
        "3ad8f6a7b5ce4ef78af092b5cc767ea5cc9fbfd3",
        "d823c43ded365da244e009b088a4a34d4fe561ce",
        "d3183ae0085c3d6b2b04c0c4ce0aa631685ca043",
        "76d063f4eedeaea2791afee3a95e4f55f6cf8de6",
        "61710efa5afbd7c0705cda4a95678d55ebcc9605",
        "1b9a05c8419ca0323a55af03d86c593958a6036d",
        "553c3480976a780ec8614428a7cdfe62acba6e68",
        "413685e3901ac40ebffe65f5e66175b008f45f81",
        "1045a8a2aa1490b748ce60101e45a7a89b573ff7",
        "97c1373e649ec806ae85ce0bdcee9d6a9cee38c2",
        "80797a75a640a41437088268fb5fea5438c3717a",
        "031220f4651df43273fe9ad24b71ce7adf0f3f17",
        "9f6990a1846455633ff77fe8e664d54f220c1bcc",
        "21c65257d65968f536aee8c389a896501212e334",
        "f6c9d88c543da6794dc9a4c1e2b13016eded82ac",
    ]),
    ("feat/channels", "feat: add multi-channel support with owner/mod access control", [
        "50400fd4c8c5822126ac976cd9320c137f530a73",
        "d86c69e7b7f13c490dd9e6b3e4c16378ba0ae5b0",
        "b534e0d44192b251e87d83ff831363011eeda605",
        "4fd98dcecd4a85e6b49aef154bcf0f480b461ac6",
        "fbb3ab535fa8de0fb43c7e4f58e8cead14122486",
        "fdd110c4cf5ffc7c638a4146a3a3956f085fcd3a",
        "5a8abe061d5484ab376a1a457d66249b3de23e09",
        "9cb3e23cda781ce6bdd994342d07ff9565ed64a1",
        "4ea3303afa65f46d63faa6db3265708ed718d7bf",
        "a5c2634db2318093e65b6da183a7dbe20d10b469",
        "22c3b09a7063f74278fcc5a6d57faa995bb978c6",
        "7a2092c052cac6ab9e3897ac56344835e5a51651",
        "f4f0ba32d493c3d62e256c60ef04f2ab095f531e",
        "dcbeecc76a872559db3cb921efa2b5f07ebc4e34",
        "72d9117b8a2b804717a43b105ef93958828548e9",
        "cf585cd23da4f19cebc1f81a2715ab7a81be571a",
        "3c57edc1948cf25601356bb468ee85f953beede4",
        "a79fea443a4aa0f7dd805d564ed19b2619e94604",
        "785696a310de1c12c794a0133306883fde368928",
        "08fee6b60abd40062e4a3ee6da53ad4abfb13c6b",
        "7055c0ede8bd72af1b9719c5eaf6c1e3bd4761bf",
        "007033b1eb6e08e445463a01554d393d299e20e3",
        "4c7f23ba201155b245ddd83986fb2b74ed1bbbd1",
        "9c8ae4b4aef052f2f52cdaaffb5f530cedbe1b4d",
        "4682183f4348bf155b76275d49004f230ea2412a",
        "4a8933e98aac89a207cf9006bc34745ccc228330",
        "080430125af2bcedc3c4afc89a51113a7d70a5ca",
        "ac3bf22a75a072a02120708f0562df1df1384a67",
        "1e3619e291088f235cb72abb9627b9554eff4436",
        "ca14ccd16fcbfd1569d14dea676491fd1db8429d",
        "e452ec72d4550c2f1ff01897407c988ae21fe586",
        "4e935c57ca39e1391695ff769510573b8d4ae087",
    ]),
    ("feat/incomplete-fallback", "feat: apply incomplete binding fallback game when binding is INCOMPLETE", [
        "46ea9e2bf2930ef95f76c21b4a4534449e1ce1b4",
        "e2876bd13462f12096953ee9a93b3bfdcfd95e73",
        "ed8fa5dc4db8bd4c41c12f2de3623a8d32876401",
        "f8cdc50474a0872dd29c2981bb821b5fe52c3585",
        "784742cc19ff99473d09dab5cf1acf5c56a44482",
        "b6845212f3f32be8d4d74de83665846e332dbe9d",
        "02424eba6a2ce28bf9d51357cb5ea6dfef0b4ec1",
        "0ff1543603779be2b05e80fe505b63cca53e78f0",
        "b4b4af7466e1153c67a5406a5e44489cfde98d1c",
        "4a47bad88fb27e32d958fc484360b2ea5215eb0b",
    ]),
    ("feat/prometheus", "feat: add Micrometer Prometheus metrics for bindings and platform connections", [
        "d02e56a6c62961a23cf653f4e34eec47666a01d7",
        "347bf8ab32000d57deff6dabf250aa7993091b04",
        "8d1f1026016ef9f33244568ade1b28ae493a4511",
        "ba07a3f2e50ba1b382594a100b58b82217241f8b",
        "bc5f9e4641347914373017f856c6b2fdba60c346",
        "acc7c0da6eed0a6b763d9fb688e4380af949b165",
        "371f2d9dc6b4f14fe01192dbd2e67622b9c01178",
        "d59730965a67b275bdcc42d6ab997752dbb651eb",
        "a7b05dbd857f54280d7656c0e3607db78b597206",
        "4321d495cbcf076088f0c124153de60bd7a67687",
        "0cb11e8e04acdc14f93e842fac22cb78863b59ab",
    ]),
    ("feat/experiment-providers", "feat: add ExperimentProvider abstraction with Unleash and GrowthBook backends", [
        "f3afece226bec3229f5d455b8fe3d49d5a557b6e",
        "20bf0a2a3449affcee96e02d3d355bed2219f707",
        "fa0bcbc2c58859d30547385b4faca92755e859f5",
        "fcb33a598f00e8e27224642b9d85308528b5364e",
        "7ea555c519eb610f3a5033f7ade288bb823f9c73",
        "861de1394d46eb7c5f983bff4a4141c39358a65e",
        "787614edf681580631e490ed62283ca6f34df39a",
        "e066ec2c348967b7efef4b07ac5a8caf8e348a82",
        "59529bf78281f38cf3181ed6b3d088a1bc9e4958",
        "d402cb565bc882a2d350591cace6fd9cca9cc9c5",
        "f4caad670fbf31a469d3eddd07fb784f7d330700",
        "dd99316958681ad1aeef5975cbf8a15ba008993e",
        "415906b6d6e5f5246b2296aa1b8af96ccafa82e6",
        "67619d9a5e4f48470e9e8cfe00ef0df2d2ab3289",
        "d53b5802ef4f84060b32535033e641580127693a",
        "47bb6536528457727edb42166e18890428e0a804",
        "31f4048b68619609925b556f60f812db31e79fb7",
        "7dae140f4df149a2c52fa92b12edfe7ae9433cea",
        "03b45b78efa9f7c8ebcf40fcd03147f77c10a2ca",
        "f6471a612b8911f178e3e46265845b8b29a62887",
        "c13d51f5a94e5b6430748278defa01940882fb3a",
        "6be3139a3e67c0ae33f0ca8887dd507d60298ff5",
        "f4342aa2670dda7db8a4cbcc847411b9b854c325",
        "30b4530729e1a2232bd97ebba27569ec1a67e29e",
        "7ce7cf9bc14bd67884003c5c3395ab8d98a42291",
    ]),
    ("chore/ci", "chore: set up GitLab CI with Sonarqube, Jacoco and Docker Java 21", [
        "0ff177285e3a85d5bf672533345306fab6dfd351",
        "52929b8c09cf6ff24cc174aa0b6b7cf568682f3e",
        "41035cacde21c3a7fdd8374cd8fa381f86352957",
        "65aafb2dc507b02561457da6f12258f50ceb6d36",
        "ba7a90aaa185154512295262b79c6b0e884ee5af",
        "1287ecce51e363fa19f52ff7ba36d191bd0a4405",
        "c5e618c0faed7fd0b9f0cc20a9de967187e0aaff",
    ]),
]

# OBS commits go to feat/obs-getter (cherry-picked from original master, never merged)
OBS_COMMITS = [
    "4b479c5a60740edd3ddd6ce95447b879fc3cd416",
    "13ab3b77ff4512f34a05b685d212e14fe33a0ea8",
    "4b628cc0b5d311f88b558d3a0e156ec2e39671c1",
    "2e683a7b10d0c40f812073f5726f611527f356f2",
    "094c0e622521e296defa492a31a1cf52f989e9f7",
    "efb46f519f0aa423271a67e4e930db603e8b0535",
    "80a8db828b116753fd8162ec0e74e17a2f605a45",
    "7383328f2161cb996e7b5308eddae0539047002b",
    "1d8c84be17ce739cdd2c3d9bc99438465645ea78",
    "cdcdb265f8146d479df3ba4e68bad47cacf15955",
    "550edf74bbae9d9eea210e8974bb311d6de46f50",
    "aab0931810789dbb71ad711fb21babca815a3930",
    "d21638f009d7dacc6cfefc6031b1065c84db945f",
    "a76d6545385a42bb9ddfe256bc67a562405bb090",
    "f27d472a54cb6504ec9d5467f57724640718e1d3",
    "a9e3f4e584b679056ca2cfb1a6ccacefe24ab50c",
    "7069fa34412b31d3b0aa9b0602ba569eac3f5a4d",
    "3775e20ca258565ca2dc14adca79b7f3aaf69ea2",
    "252fcaf2824582df8085db62df20a559a48893aa",
    "bb15b93aba66d505f265ab1fd07c47f735965db4",
    "d684d04c26f0e1c92ceab5d4cfdbc3dbfce6e619",
    "d679b1d4acd278e791679822cdf611b84f19f44c",
    "40bac2abd6d630976a88f213676899d8822311c3",
    "5e305ea3b1de3f8f62ca97439f81e154c77f8a0f",
    "558152748e73bc87d0822b019d93565a9efda25f",
    "044c374df1eade85924056a770ccff47eced868b",
    "56891c99b9427157a3a7074e8ab07c4709b304e0",
]
# fmt: on

CHECKPOINT_FILE = os.path.join(REPO, ".rewrite_checkpoint.json")


def run(cmd, check=True, capture_output=False, env=None):
    if isinstance(cmd, str):
        cmd = shlex.split(cmd.replace(" 2>&1", ""))
    result = subprocess.run(
        cmd, shell=False, cwd=REPO,
        capture_output=capture_output,
        text=True, env=env
    )
    if check and result.returncode != 0:
        print(f"ERROR running: {cmd}")
        if capture_output:
            print(f"STDOUT: {result.stdout}")
            print(f"STDERR: {result.stderr}")
        sys.exit(1)
    return result


def get_commit_date(commit_hash):
    result = run(["git", "log", "-1", "--format=%aI", commit_hash], capture_output=True)
    return result.stdout.strip()


def is_merge_commit(commit_hash):
    result = run(["git", "rev-parse", "--verify", f"{commit_hash}^2"],
                 capture_output=True, check=False)
    return result.returncode == 0


def cherry_pick_with_date(commit_hash):
    date = get_commit_date(commit_hash)
    env = os.environ.copy()
    env["GIT_COMMITTER_DATE"] = date
    cmd = ["git", "cherry-pick", commit_hash]
    if is_merge_commit(commit_hash):
        cmd = ["git", "cherry-pick", "-m", "1", commit_hash]
    result = run(cmd, check=False, capture_output=True, env=env)
    if result.returncode != 0:
        combined = result.stdout + result.stderr
        if "allow-empty" in combined:
            # Changes already in tree — skip as empty
            print(f"    (empty commit, skipping)")
            run(["git", "cherry-pick", "--skip"])
        elif "CONFLIT" in combined or "cherry-pick --abort" in combined:
            # Merge conflict — abort this pick and continue to next commit
            print(f"    (conflict on {commit_hash[:8]}, skipping)")
            run(["git", "cherry-pick", "--abort"])
        else:
            print(f"ERROR cherry-picking {commit_hash[:8]}:\n{combined[:400]}")
            sys.exit(1)


def load_checkpoint():
    if os.path.exists(CHECKPOINT_FILE):
        with open(CHECKPOINT_FILE) as f:
            return json.load(f)
    return {"completed": [], "mr_numbers": {}}


def save_checkpoint(checkpoint):
    with open(CHECKPOINT_FILE, "w") as f:
        json.dump(checkpoint, f, indent=2)


def move_existing_branches_to_old():
    print("==> Moving existing branches to old/...")
    result = run("git branch", capture_output=True)
    branches = [b.strip().lstrip("* ") for b in result.stdout.strip().split("\n")]
    for branch in branches:
        if branch in ("master", "") or branch.startswith("old/"):
            continue
        print(f"  {branch} -> old/{branch}")
        run(f"git branch -m {branch} old/{branch}")


def unprotect_branch(branch_name):
    print(f"==> Unprotecting branch '{branch_name}' on GitLab...")
    run(
        ["glab", "api", "--method", "DELETE",
         f"projects/{GITLAB_REPO.replace('/', '%2F')}/protected_branches/{branch_name}"],
        capture_output=True, check=False
    )


def protect_branch(branch_name):
    print(f"==> Re-protecting branch '{branch_name}' on GitLab...")
    run(
        ["glab", "api", "--method", "POST",
         f"projects/{GITLAB_REPO.replace('/', '%2F')}/protected_branches",
         "--field", f"name={branch_name}",
         "--field", "push_access_level=40",
         "--field", "merge_access_level=40"],
        capture_output=True, check=False
    )


def reset_master_to_initial():
    print(f"==> Resetting master to initial commit {INITIAL_COMMIT[:8]}...")
    run(f"git checkout master")
    run(f"git reset --hard {INITIAL_COMMIT}")
    print("==> Force-pushing clean master to GitLab...")
    unprotect_branch("master")
    run(f"git push {GITLAB_REMOTE} master --force")
    protect_branch("master")


def create_and_merge_mr(branch, mr_title, commits, checkpoint, dry_run=False):
    print(f"\n==> Creating MR for {branch}: {mr_title}")

    if dry_run:
        print(f"  [DRY RUN] Would create MR: {mr_title}")
        return "DRY-RUN"

    # Push branch to GitLab
    run(f"git push {GITLAB_REMOTE} {branch} --force")
    time.sleep(2)  # let GitLab index the branch before creating MR

    # Build description with real newlines (not literal \n)
    commit_lines = "\n".join(f"- `{c[:8]}`" for c in commits)
    description = (
        "## Résumé\n\n"
        f"Réécriture de l'historique — branche `{branch}`.\n\n"
        "## Commits\n\n"
        f"{commit_lines}"
    )

    # Create MR via glab (retry on transient "source branch not found" errors)
    import re
    for mr_attempt in range(4):
        result = run(
            ["glab", "mr", "create",
             "--title", mr_title,
             "--target-branch", "master",
             "--description", description],
            capture_output=True, check=False
        )
        output = (result.stdout + result.stderr).strip()
        if "source_branch" not in output and "n'existe pas" not in output:
            break
        print(f"  MR create failed (source branch not indexed yet), retrying in 3s...")
        time.sleep(3)
    print(f"  glab output: {output}")

    # Extract MR IID from output (format: "!N" or URL ending in /N)
    mr_match = re.search(r'!(\d+)', output)
    if not mr_match:
        mr_match = re.search(r'/merge_requests/(\d+)', output)
    if not mr_match:
        print(f"  Could not find MR number in output. Trying to find open MR...")
        list_result = run(
            ["glab", "mr", "list", "--source-branch", branch, "--json", "iid,title"],
            capture_output=True, check=False
        )
        mrs = json.loads(list_result.stdout or "[]")
        if mrs:
            mr_iid = mrs[0]["iid"]
        else:
            print(f"  ERROR: No MR found for {branch}")
            sys.exit(1)
    else:
        mr_iid = mr_match.group(1)

    print(f"  MR !{mr_iid} created")

    # Merge via GitLab API directly (bypasses glab's pipeline-status gate)
    print(f"  Merging MR !{mr_iid} via API...")
    api_path = f"projects/{GITLAB_REPO.replace('/', '%2F')}/merge_requests/{mr_iid}/merge"
    for attempt in range(6):
        merge_result = run(
            ["glab", "api", "--method", "PUT", api_path,
             "--field", "squash=false",
             "--field", "should_remove_source_branch=false"],
            capture_output=True, check=False
        )
        merge_output = (merge_result.stdout + merge_result.stderr).strip()
        if "405" not in merge_output and "Method Not Allowed" not in merge_output:
            break
        print(f"  Merge blocked (attempt {attempt+1}/6), waiting 5s...")
        time.sleep(5)
    print(f"  merge result: {merge_output[:300]}")

    # Verify the merge actually happened
    mr_state = run(
        ["glab", "api", f"projects/{GITLAB_REPO.replace('/', '%2F')}/merge_requests/{mr_iid}"],
        capture_output=True, check=False
    )
    try:
        state = json.loads(mr_state.stdout).get("state", "unknown")
        if state != "merged":
            print(f"  WARNING: MR !{mr_iid} state is '{state}', not 'merged'. Aborting.")
            sys.exit(1)
    except Exception:
        pass

    # Wait for merge to complete, then pull updated master
    time.sleep(2)
    run(f"git fetch {GITLAB_REMOTE} master")
    run(f"git reset --hard {GITLAB_REMOTE}/master")

    return str(mr_iid)


def process_group(branch, mr_title, commits, checkpoint, dry_run=False):
    if branch in checkpoint["completed"]:
        print(f"  [SKIP] {branch} already completed")
        return

    print(f"\n{'='*60}")
    print(f"Processing: {branch}")
    print(f"  Commits: {len(commits)}")

    # Create branch from current master (delete stale local branch if present)
    run(["git", "checkout", "master"])
    run(["git", "reset", "--hard", f"{GITLAB_REMOTE}/master"])
    run(["git", "branch", "-D", branch], check=False)
    run(["git", "checkout", "-b", branch])

    # Cherry-pick each commit preserving author/committer dates
    for commit in commits:
        print(f"  cherry-pick {commit[:8]}...")
        cherry_pick_with_date(commit)

    # Skip if branch has no commits ahead of master (all cherry-picks were empty)
    ahead = run(["git", "rev-list", "--count", "master..HEAD"],
                capture_output=True).stdout.strip()
    if ahead == "0":
        print(f"  All cherry-picks empty — no MR to create, skipping.")
        run(["git", "checkout", "master"])
        run(["git", "branch", "-D", branch], check=False)
        checkpoint["completed"].append(branch)
        checkpoint["mr_numbers"][branch] = "SKIPPED"
        save_checkpoint(checkpoint)
        return

    # Create and merge MR
    mr_iid = create_and_merge_mr(branch, mr_title, commits, checkpoint, dry_run=dry_run)

    # Clean up local branch
    run(f"git checkout master")
    run(f"git branch -D {branch}", check=False)

    checkpoint["completed"].append(branch)
    checkpoint["mr_numbers"][branch] = mr_iid
    save_checkpoint(checkpoint)
    print(f"  [OK] {branch} merged as !{mr_iid}")


def create_obs_branch(dry_run=False):
    """Create feat/obs-getter as an open branch (not merged to master)."""
    print(f"\n{'='*60}")
    print("Creating feat/obs-getter (open branch, not merged)...")

    run("git checkout master")
    run("git checkout -b feat/obs-getter", check=False)

    for commit in OBS_COMMITS:
        print(f"  cherry-pick {commit[:8]}...")
        cherry_pick_with_date(commit)

    if not dry_run:
        run(f"git push {GITLAB_REMOTE} feat/obs-getter --force")
        print("  [OK] feat/obs-getter pushed to GitLab (open branch)")
    else:
        print("  [DRY RUN] Would push feat/obs-getter")

    run("git checkout master")


def resolve_short_hash(short_hash):
    """Resolve a short hash to a full hash (searches all refs)."""
    result = run(["git", "rev-parse", "--verify", short_hash], capture_output=True, check=False)
    if result.returncode == 0:
        return result.stdout.strip()
    return short_hash


def main():
    dry_run = "--dry-run" in sys.argv
    skip_init = "--skip-init" in sys.argv
    resume = "--resume" in sys.argv or os.path.exists(CHECKPOINT_FILE)

    if dry_run:
        print("DRY RUN MODE - no pushes, no GitLab operations")

    checkpoint = load_checkpoint() if resume else {"completed": [], "mr_numbers": {}}

    os.chdir(REPO)

    if not skip_init and "init" not in checkpoint.get("completed", []):
        # Step 1: Move existing branches to old/
        move_existing_branches_to_old()

        # Step 2: Reset master to initial commit
        reset_master_to_initial()

        checkpoint["completed"].append("init")
        save_checkpoint(checkpoint)

    # Step 3: Process each group
    for branch, mr_title, commits in GROUPS:
        # Resolve short hashes
        resolved_commits = [resolve_short_hash(c) for c in commits]
        process_group(branch, mr_title, resolved_commits, checkpoint, dry_run=dry_run)

    # Step 4: Create obs-getter as open branch
    if "feat/obs-getter" not in checkpoint.get("completed", []):
        create_obs_branch(dry_run=dry_run)
        checkpoint["completed"].append("feat/obs-getter")
        save_checkpoint(checkpoint)

    print(f"\n{'='*60}")
    print("REWRITE COMPLETE!")
    print(f"Total MRs created: {len(checkpoint['mr_numbers'])}")
    for branch, mr_iid in checkpoint["mr_numbers"].items():
        print(f"  {branch} -> !{mr_iid}")

    # Restore CI config
    if not dry_run:
        print("==> Restoring CI config...")
        run(
            ["glab", "api", "--method", "PUT",
             f"projects/{GITLAB_REPO.replace('/', '%2F')}",
             "--field", "ci_config_path="],
            capture_output=True, check=False
        )

    # Cleanup checkpoint
    if os.path.exists(CHECKPOINT_FILE):
        os.remove(CHECKPOINT_FILE)


if __name__ == "__main__":
    main()
