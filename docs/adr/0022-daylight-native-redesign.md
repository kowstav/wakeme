# ADR 0022: Daylight native visual system

Status: implemented on the review branch; release/physical gates remain separate.

The owner rejected the previous welcome and requested a full native visual redesign using directly inspected app references. Adopt the Alarmi time-card/profile structures and Habit Tracker form/category hierarchy, normalized into one lavender/ink/violet system. Details, actual adaptations and coverage are in [the screen map](../design/daylight-screen-map.md).

Keep shared token roles and contrast assertions as the source for Android and iOS. Bundle Manrope with its OFL license, retain official identity-provider branding, use original vector scenes and real-name initials, and keep real scheduling, readiness, consent and escape behavior. Existing family identifiers remain stable for callers even where their colors change.

Rebuild primary compositions and propagate the shared system to the remaining feature screens. Do not fabricate statistics, photos, product prices or controls to imitate a reference. Keep alarm creation reachable above long lists with a real accessible action.

Android staging compilation/lint, unit tests, native renders, asset/configuration/signature checks establish a review candidate. Native emulator journeys, iOS compile/simulator evidence and physical-device acceptance establish separate later gates. This decision supersedes ADR 0021's palette/type/art direction, retaining its useful native architecture and safety constraints.
