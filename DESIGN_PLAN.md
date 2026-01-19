# PickApp Modern Design Implementation Plan

## Overview

Modernize PickApp's UI following **Google Material Design 3** guidelines and **Material 3 Expressive** principles (Android 16). The app already uses Compose Material 3 but with minimal customization.

## User Selections
- **Color Theme**: Industrial Blue - professional, trustworthy
- **Navigation**: Bottom navigation for main screens (Home, Documents, Profile)
- **Scope**: Full redesign (all phases)
- **Font**: Space Grotesk - modern geometric sans-serif

## Current State Analysis

### What's Already Good
- Uses Jetpack Compose with Material 3 components
- Dynamic color support for Android 12+
- Proper use of `stringResource()` for all text
- Clean MVVM architecture with state flows

### What Needs Improvement
- **Colors**: Only 6 basic colors defined (Purple40/80, PurpleGrey40/80, Pink40/80)
- **Typography**: Only `bodyLarge` customized; all others use defaults
- **Shapes**: No custom shapes defined
- **Components**: Basic usage without proper visual hierarchy
- **Animations**: No animations implemented
- **Navigation**: No bottom navigation or navigation rail for device adaptation
- **Visual polish**: Basic layouts without modern design patterns

---

## Implementation Plan

### Phase 1: Theme Foundation

#### 1.1 Generate Professional Color Scheme
**File**: `app/src/main/java/ua/com/programmer/pick/ui/theme/Color.kt`

**Industrial Blue** warehouse/logistics color palette:
- **Primary**: Blue (#1565C0) - main brand, buttons, active states
- **Secondary**: Teal (#00897B) - success states, sync complete
- **Tertiary**: Amber (#FF8F00) - warnings, pending items
- **Error**: Red (#D32F2F) - errors, offline states
- **Surface**: Clean whites/grays for content areas

Define complete color roles:
- `primary`, `onPrimary`, `primaryContainer`, `onPrimaryContainer`
- `secondary`, `onSecondary`, `secondaryContainer`, `onSecondaryContainer`
- `tertiary`, `onTertiary`, `tertiaryContainer`, `onTertiaryContainer`
- `background`, `onBackground`, `surface`, `onSurface`
- `surfaceVariant`, `onSurfaceVariant`, `outline`, `outlineVariant`
- `error`, `onError`, `errorContainer`, `onErrorContainer`

#### 1.2 Add Space Grotesk Font
**Files**:
- `app/src/main/res/font/space_grotesk_regular.ttf`
- `app/src/main/res/font/space_grotesk_medium.ttf`
- `app/src/main/res/font/space_grotesk_semibold.ttf`
- `app/src/main/res/font/space_grotesk_bold.ttf`

Download Space Grotesk from Google Fonts and add font files to `res/font/` directory.

#### 1.3 Define Custom Typography Scale with Space Grotesk
**File**: `app/src/main/java/ua/com/programmer/pick/ui/theme/Type.kt`

```kotlin
val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.space_grotesk_bold, FontWeight.Bold)
)
```

**Type Scale**:
```
displayLarge:  57sp, Bold      - Not used in this app
displayMedium: 45sp, Bold      - Not used in this app
displaySmall:  36sp, Bold      - Splash logo
headlineLarge: 32sp, SemiBold  - Screen titles
headlineMedium: 28sp, SemiBold - Section headers
headlineSmall: 24sp, Medium    - Card titles
titleLarge:    22sp, Medium    - Document titles
titleMedium:   16sp, Medium    - List item titles
titleSmall:    14sp, Medium    - Subtitles
bodyLarge:     16sp, Normal    - Primary body text
bodyMedium:    14sp, Normal    - Secondary body text
bodySmall:     12sp, Normal    - Captions
labelLarge:    14sp, Medium    - Button text
labelMedium:   12sp, Medium    - Chips, badges
labelSmall:    11sp, Medium    - Small labels
```

#### 1.4 Define Custom Shapes
**File**: `app/src/main/java/ua/com/programmer/pick/ui/theme/Shape.kt` (new)

```kotlin
extraSmall: 4.dp  - Small chips, badges
small:      8.dp  - Input fields, small cards
medium:     12.dp - Standard cards, dialogs
large:      16.dp - Large cards, sheets
extraLarge: 28.dp - FABs, prominent elements
```

#### 1.5 Update Theme.kt
**File**: `app/src/main/java/ua/com/programmer/pick/ui/theme/Theme.kt`

- Add shapes to MaterialTheme
- Improve dynamic color fallback handling
- Add status bar/navigation bar theming

---

### Phase 2: Component Library Improvements

#### 2.1 Enhanced Common Components
**Directory**: `app/src/main/java/ua/com/programmer/pick/presentation/common/`

| Component | Enhancement |
|-----------|-------------|
| `SyncStatusChip.kt` | Use `FilterChip` or `AssistChip` with icons, rounded shape |
| `OfflineBanner.kt` | Add warning icon, animate entrance/exit |
| `LoadingLogo.kt` | Add branded icon/logo, pulsing animation |
| `QuantityStepper.kt` | Use `FilledIconButton` for +/-, better spacing |
| `SearchBar.kt` | Use M3 `SearchBar` or `DockedSearchBar` component |
| `EmptyState.kt` | Add illustration placeholder, better typography |
| `DocumentListItem.kt` | Improve card elevation, add leading icon |
| `DocumentLineRow.kt` | Better visual hierarchy, progress indicator |

#### 2.2 New Common Components

| Component | Purpose |
|-----------|---------|
| `PickAppBar.kt` | Standardized TopAppBar with consistent styling |
| `PickCard.kt` | Styled card with consistent elevation and shape |
| `StatusIndicator.kt` | Online/offline dot indicator with animation |
| `LoadingButton.kt` | Button with integrated loading state |
| `SectionHeader.kt` | Consistent section headers with dividers |

---

### Phase 3: Screen Redesigns

#### 3.1 Splash Screen Enhancement
**File**: `app/src/main/java/ua/com/programmer/pick/presentation/splash/SplashScreen.kt`

- Add app icon/logo (vector drawable)
- Animated loading indicator
- Smooth fade transition to next screen

#### 3.2 Login Screen Modernization
**File**: `app/src/main/java/ua/com/programmer/pick/presentation/auth/LoginScreen.kt`

- Add branding header with logo
- Use `FilledTextField` instead of `OutlinedTextField` for primary input
- Add password visibility toggle
- Improve button hierarchy (filled primary button)
- Add subtle background gradient or pattern
- Keyboard IME actions (next/done)

#### 3.3 Home Screen Dashboard
**File**: `app/src/main/java/ua/com/programmer/pick/presentation/home/HomeScreen.kt`

- Replace basic buttons with dashboard cards:
  - Documents card with count badge
  - Quick actions grid
  - Recent activity preview
- Use `LargeTopAppBar` with collapse behavior
- Add status section with visual indicators
- Implement pull-to-refresh pattern

#### 3.4 Documents List Screen
**File**: `app/src/main/java/ua/com/programmer/pick/presentation/documents/DocumentsScreen.kt`

- Add filtering chips (by type, status)
- Implement search bar at top
- Improve list item design:
  - Leading icon by document type
  - Progress indicator (actual/planned)
  - Status badge
  - Swipe actions (if applicable)
- Add FAB for new document (if applicable)
- Empty state illustration

#### 3.5 Document Detail Screen
**File**: `app/src/main/java/ua/com/programmer/pick/presentation/document/DocumentDetailScreen.kt`

- Sticky header with document info
- Improved line item cards:
  - Product image placeholder
  - Clear quantity display
  - Barcode scan button
- Bottom action bar for save/complete
- Progress indicator (lines completed vs total)

#### 3.6 Settings Screen
**File**: `app/src/main/java/ua/com/programmer/pick/presentation/settings/SettingsScreen.kt`

- Use preference-style list items
- Group settings with section headers
- Add icons to each setting item
- Use switches for toggle options
- Destructive actions (clear cache) in different color

#### 3.7 Profile Screen
**File**: `app/src/main/java/ua/com/programmer/pick/presentation/profile/ProfileScreen.kt`

- Add avatar placeholder with user initials
- Improve user info card layout
- Better form styling for edit fields

---

### Phase 4: Navigation Enhancement

#### 4.1 Bottom Navigation (for main screens)
**File**: `app/src/main/java/ua/com/programmer/pick/presentation/navigation/NavGraph.kt`

Add `NavigationBar` for primary destinations:
- Home (Dashboard)
- Documents
- Profile

#### 4.2 Navigation Transitions
Add animated transitions between screens:
- Shared element transitions for list → detail
- Fade transitions for auth flow
- Slide transitions for settings/profile

---

### Phase 5: Animations & Motion

#### 5.1 Micro-interactions
- Button press feedback (ripple already handled by M3)
- Loading state transitions
- List item appearance animations

#### 5.2 Screen Transitions
- `AnimatedNavHost` for route transitions
- Staggered list animations using `AnimatedVisibility`

#### 5.3 State Transitions
- Animate sync status changes
- Smooth online/offline indicator
- Quantity stepper value changes

---

### Phase 6: Polish & Accessibility

#### 6.1 Visual Polish
- Consistent spacing (8dp grid system)
- Proper elevation hierarchy
- Tonal surface colors for depth

#### 6.2 Accessibility
- Content descriptions for all icons
- Minimum touch targets (48dp)
- Sufficient color contrast (4.5:1 for text)
- Support for large fonts

---

## File Changes Summary

### New Files
- `res/font/space_grotesk_regular.ttf` - Space Grotesk Regular
- `res/font/space_grotesk_medium.ttf` - Space Grotesk Medium
- `res/font/space_grotesk_semibold.ttf` - Space Grotesk SemiBold
- `res/font/space_grotesk_bold.ttf` - Space Grotesk Bold
- `ui/theme/Shape.kt` - Custom shapes definition
- `presentation/common/PickAppBar.kt` - Standardized app bar
- `presentation/common/PickCard.kt` - Styled card component
- `presentation/common/StatusIndicator.kt` - Animated status dot
- `presentation/common/LoadingButton.kt` - Button with loading state
- `presentation/common/SectionHeader.kt` - Section headers
- `res/drawable/ic_app_logo.xml` - App logo vector
- Various icons: `ic_document.xml`, `ic_sync.xml`, etc.

### Modified Files
- `ui/theme/Color.kt` - Complete color palette
- `ui/theme/Type.kt` - Full typography scale
- `ui/theme/Theme.kt` - Add shapes, improve theming
- `presentation/common/SyncStatusChip.kt` - Use proper chip component
- `presentation/common/OfflineBanner.kt` - Add icon, animation
- `presentation/common/LoadingLogo.kt` - Add branding
- `presentation/common/QuantityStepper.kt` - Better styling
- `presentation/common/SearchBar.kt` - Use M3 SearchBar
- `presentation/common/EmptyState.kt` - Better design
- `presentation/common/DocumentListItem.kt` - Enhanced card
- `presentation/common/DocumentLineRow.kt` - Better hierarchy
- `presentation/splash/SplashScreen.kt` - Animation, logo
- `presentation/auth/LoginScreen.kt` - Modern design
- `presentation/home/HomeScreen.kt` - Dashboard layout
- `presentation/documents/DocumentsScreen.kt` - Filters, search
- `presentation/document/DocumentDetailScreen.kt` - Better UX
- `presentation/settings/SettingsScreen.kt` - Preference style
- `presentation/profile/ProfileScreen.kt` - Avatar, layout
- `presentation/navigation/NavGraph.kt` - Bottom nav, transitions
- `res/values/strings.xml` - New UI strings

### Dependencies (optional additions)
- `material-icons-extended` - More icon options
- `accompanist-navigation-animation` - Animated navigation (if needed)

---

## Verification

1. **Build**: Run `./gradlew assembleDebug` - ensure no compilation errors
2. **Visual Review**: Install APK and verify each screen
3. **Theme Testing**:
   - Test light/dark mode switching
   - Test dynamic color on Android 12+ device
   - Test on Android 7+ device (fallback colors)
4. **Accessibility**: Run accessibility scanner
5. **Device Sizes**: Test on phone and tablet emulators

---

## Implementation Order (Recommended)

1. **Theme Foundation** (Phase 1) - Sets the base for all other work
2. **Component Library** (Phase 2) - Creates reusable building blocks
3. **Core Screens** (Phase 3) - Login, Home, Documents
4. **Secondary Screens** (Phase 3) - Detail, Settings, Profile
5. **Navigation** (Phase 4) - Bottom nav, transitions
6. **Animations** (Phase 5) - Final polish
7. **Accessibility** (Phase 6) - Final verification

---

## References

- [Material Design 3](https://m3.material.io/)
- [Material Design 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3)
- [Material 3 Expressive](https://www.androidauthority.com/google-material-3-expressive-features-changes-availability-supported-devices-3556392/)
- [Space Grotesk Font](https://fonts.google.com/specimen/Space+Grotesk)
