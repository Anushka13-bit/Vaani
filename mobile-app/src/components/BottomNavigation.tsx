import React from 'react';
import {View, Text, TouchableOpacity, StyleSheet} from 'react-native';
import Icon from 'react-native-vector-icons/Feather';
import {colors} from '../theme/colors';
import {typography} from '../theme/typography';

export type NavKey = 'home' | 'history' | 'phrases' | 'settings';

interface NavItemConfig {
  key: NavKey;
  label: string;
  iconName: string;
}

const NAV_ITEMS: NavItemConfig[] = [
  {key: 'home', label: 'Home', iconName: 'home'},
  {key: 'history', label: 'History', iconName: 'clock'},
  {key: 'phrases', label: 'Phrases', iconName: 'book-open'},
  {key: 'settings', label: 'Settings', iconName: 'settings'},
];

interface BottomNavigationProps {
  active: NavKey;
  onHomePress?: () => void;
  onHistoryPress?: () => void;
  onPhrasesPress?: () => void;
  onSettingsPress?: () => void;
}

/**
 * Shared bottom tab bar used across the main screens.
 * All four tabs navigate to their respective screens.
 */
const BottomNavigation: React.FC<BottomNavigationProps> = ({
  active,
  onHomePress,
  onHistoryPress,
  onPhrasesPress,
  onSettingsPress,
}) => {
  const handlePress = (key: NavKey) => {
    switch (key) {
      case 'home':
        onHomePress?.();
        break;
      case 'history':
        onHistoryPress?.();
        break;
      case 'phrases':
        onPhrasesPress?.();
        break;
      case 'settings':
        onSettingsPress?.();
        break;
    }
  };

  return (
    <View style={styles.container}>
      {NAV_ITEMS.map(item => {
        const isActive = item.key === active;
        const tintColor = isActive ? colors.navActive : colors.navInactive;
        return (
          <TouchableOpacity
            key={item.key}
            style={styles.item}
            activeOpacity={0.7}
            onPress={() => handlePress(item.key)}>
            <Icon name={item.iconName} size={22} color={tintColor} />
            <Text style={[styles.label, {color: tintColor}]}>
              {item.label}
            </Text>
          </TouchableOpacity>
        );
      })}
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flexDirection: 'row',
    justifyContent: 'space-around',
    alignItems: 'center',
    backgroundColor: colors.navBackground,
    minHeight: 64,
    paddingVertical: 6,
    borderTopWidth: 1,
    borderTopColor: colors.navBorder,
  },
  item: {
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 2,
    paddingHorizontal: 12,
  },
  label: {
    ...typography.navigation,
    marginTop: 4,
  },
});

export default BottomNavigation;
