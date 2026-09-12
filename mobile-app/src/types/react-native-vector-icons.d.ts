// react-native-vector-icons ships no bundled types and has no @types package,
// so every icon-set import (Feather, MaterialIcons, ...) resolves to an implicit
// any under `strict`, which buried real errors in `npm run tsc` output.
declare module 'react-native-vector-icons/*' {
  import type { Component } from 'react';
  import type { TextProps } from 'react-native';

  export interface IconProps extends TextProps {
    name: string;
    size?: number;
    color?: string;
  }

  export default class Icon extends Component<IconProps> {}
}
