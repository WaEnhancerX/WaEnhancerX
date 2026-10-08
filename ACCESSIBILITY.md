# Accessibility Statement

At **WAEX**, we are committed to making our application, documentation, and tooling accessible to everyone, including individuals with disabilities. We strive to provide an intuitive, high-quality, and inclusive user experience across all supported devices and screen environments.

---

## 1. Our Accessibility Commitment

We follow Android accessibility best practices and the [Web Content Accessibility Guidelines (WCAG) 2.1 Level AA](https://www.w3.org/WAI/WCAG21/quickref/) principles (Perceivable, Operable, Understandable, and Robust) across both our Jetpack Compose application and web properties:

- **Screen Readers**: Ensuring full compatibility with Android TalkBack and other assistive technologies by providing meaningful `contentDescription` tags and semantics.
- **Touch Target Sizing**: Adhering to Android's minimum recommended touch target size of 48x48 dp for interactive elements (buttons, toggles, icons, list items).
- **Color Contrast & Dynamic Theming**: Providing high-contrast color palettes with WCAG AA minimum contrast ratios (4.5:1 for normal text, 3:1 for large text and UI components) in both Light and Dark themes, supporting Material You dynamic color adaptation.
- **Dynamic Text & Font Scaling**: Full support for system-wide font scaling, display size adjustments, and bold text without clipping, truncation, or broken layouts.
- **Reduced Motion**: Respecting system settings for reduced animations and motion preferences.
- **Keyboard & Hardware Navigation**: Ensuring logical focus traversal and support for D-pad, external keyboards, and switch access devices.

---

## 2. Supported Environments & Assistive Technologies

WAEX is developed and tested for compatibility with:

- **Screen Readers**:
  - Android TalkBack (Android 10 - 15+)
  - Samsung Voice Assistant
- **Input Methods**:
  - Standard multi-touch input
  - Hardware / Bluetooth keyboard navigation (Tab, Arrow keys, Enter/Space)
  - Android Switch Access and Voice Access
- **Display Configurations**:
  - System Font Scaling (up to 200%)
  - High Contrast Text mode
  - Color correction / Inversion modes
  - Dark Mode and AMOLED Black themes

---

## 3. Known Limitations & Ongoing Work

While we continuously work toward comprehensive accessibility coverage, some areas are actively being enhanced:

1. **Third-Party Injected Elements**:
   - Modded UI elements injected directly into the WhatsApp host process may depend on upstream WhatsApp view hierarchy semantics, which can vary between WhatsApp app releases.
2. **Complex Visual Modding Overlays**:
   - Certain custom canvas-drawn elements or real-time animation tools may require additional semantic descriptions for non-visual navigation.
3. **Advanced Logcat / Hook Diagnostics**:
   - Raw stack traces and hook diagnostic viewers are text-dense and may currently be more challenging to navigate on smaller screens with high magnification.

We prioritize these areas in our roadmap and welcome community feedback to improve them.

---

## 4. How to Report Accessibility Barriers

If you encounter any accessibility issues, barriers, or have suggestions on how we can improve usability:

1. **GitHub Issues**:
   - Open an issue using our [GitHub Issues Tracker](https://github.com/WaEnhancerX/WaEnhancerX/issues).
   - Tag the issue with the `accessibility` / `a11y` label if possible.
   - Include details such as your device model, Android version, assistive tools in use (e.g., TalkBack version, font scale factor), and a description of the barrier.
2. **Community Discussions**:
   - Join and share feedback in our official [Telegram Community](https://t.me/waenhancerx).
3. **Private Contact**:
   - For direct communication, reach out via [hello@mubashar.dev](mailto:hello@mubashar.dev) or the contact channels listed in our [SECURITY.md](SECURITY.md).

---

## 5. Assessment and Continuous Improvement

We evaluate WAEX accessibility via:
- Automated accessibility scanners (Android Accessibility Scanner, Compose UI testing semantics assertions).
- Manual testing using Android TalkBack, Switch Access, and physical keyboards.
- Direct feedback and reviews from the open-source community.

*Last updated: October 2026*
