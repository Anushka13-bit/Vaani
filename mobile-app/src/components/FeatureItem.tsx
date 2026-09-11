import React from 'react';
import {View, Text, StyleSheet} from 'react-native';
import Icon from 'react-native-vector-icons/MaterialIcons';
import {colors} from '../theme/colors';

interface FeatureItemProps {
  type?: 'mic' | 'appGrid' | 'user';
  iconName?: string;
  label: string;
}

/**
 * A single feature row on the Welcome/Home screen:
 * - 42px icon element (orange-red circle for mic/user, or custom 2x2 four-color app grid)
 * - Heavy bold 16px label
 */
const FeatureItem: React.FC<FeatureItemProps> = ({
  type = 'mic',
  iconName,
  label,
}) => {
  const renderIcon = () => {
    if (type === 'appGrid') {
      return (
        <View style={styles.appGridContainer}>
          <View style={styles.appGridRow}>
            <View style={[styles.appGridTile, {backgroundColor: '#EA4335'}]} />
            <View style={[styles.appGridTile, {backgroundColor: '#34A853'}]} />
          </View>
          <View style={styles.appGridRow}>
            <View style={[styles.appGridTile, {backgroundColor: '#4285F4'}]} />
            <View style={[styles.appGridTile, {backgroundColor: '#FBBC05'}]} />
          </View>
        </View>
      );
    }

    const glyphName =
      type === 'user' ? 'person' : iconName === 'lock' ? 'apps' : 'mic';

    return (
      <View style={styles.iconCircle}>
        <Icon name={glyphName} size={22} color={colors.white} />
      </View>
    );
  };

  return (
    <View style={styles.row}>
      <View style={styles.iconWrapper}>{renderIcon()}</View>
      <Text style={styles.label}>{label}</Text>
    </View>
  );
};

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 20,
  },
  iconWrapper: {
    width: 42,
    height: 42,
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: 16,
  },
  iconCircle: {
    width: 42,
    height: 42,
    borderRadius: 21,
    backgroundColor: colors.iconCircleOrange,
    alignItems: 'center',
    justifyContent: 'center',
  },
  appGridContainer: {
    width: 32,
    height: 32,
    justifyContent: 'center',
    alignItems: 'center',
  },
  appGridRow: {
    flexDirection: 'row',
    gap: 3,
    marginBottom: 3,
  },
  appGridTile: {
    width: 14,
    height: 14,
    borderRadius: 3.5,
  },
  label: {
    fontSize: 16,
    fontWeight: '700',
    color: colors.textPrimary,
    lineHeight: 21,
    flexShrink: 1,
  },
});

export default FeatureItem;
