import React from 'react';
import {View, Text, TouchableOpacity, StyleSheet} from 'react-native';
import Icon from 'react-native-vector-icons/MaterialIcons';
import {colors} from '../theme/colors';

interface SettingsItemProps {
  iconName: string;
  iconBgColor: string;
  title: string;
  subtitle: string;
  onPress?: () => void;
}

/**
 * A single row on the Settings screen: 42px colored icon circle, title/subtitle, and right chevron.
 */
const SettingsItem: React.FC<SettingsItemProps> = ({
  iconName,
  iconBgColor,
  title,
  subtitle,
  onPress,
}) => {
  return (
    <TouchableOpacity
      style={styles.card}
      activeOpacity={onPress ? 0.7 : 1}
      onPress={onPress}
      disabled={!onPress}>
      <View style={[styles.iconCircle, {backgroundColor: iconBgColor}]}>
        <Icon name={iconName} size={22} color={colors.white} />
      </View>
      <View style={styles.textContainer}>
        <Text style={styles.title}>{title}</Text>
        <Text style={styles.subtitle}>{subtitle}</Text>
      </View>
      <Icon name="chevron-right" size={22} color={colors.textMuted} />
    </TouchableOpacity>
  );
};

const styles = StyleSheet.create({
  card: {
    height: 68,
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: colors.cardBackground,
    borderRadius: 16,
    paddingHorizontal: 16,
    marginBottom: 10,
    borderWidth: 1,
    borderColor: colors.cardBorder,
    shadowColor: '#000000',
    shadowOffset: {width: 0, height: 1},
    shadowOpacity: 0.04,
    shadowRadius: 3,
    elevation: 1,
  },
  iconCircle: {
    width: 42,
    height: 42,
    borderRadius: 21,
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: 14,
  },
  textContainer: {
    flex: 1,
    justifyContent: 'center',
  },
  title: {
    fontSize: 16,
    fontWeight: '700',
    color: colors.textPrimary,
    lineHeight: 20,
  },
  subtitle: {
    fontSize: 13,
    fontWeight: '400',
    color: colors.textSecondary,
    marginTop: 2,
    lineHeight: 16,
  },
});

export default SettingsItem;

