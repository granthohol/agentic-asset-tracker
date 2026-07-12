import * as $protobuf from "protobufjs";
import Long = require("long");

/** Namespace assettracker. */
export namespace assettracker {

    /** Namespace telemetry. */
    namespace telemetry {

        /** DroneStatus enum. */
        enum DroneStatus {

            /** DRONE_STATUS_UNSPECIFIED value */
            DRONE_STATUS_UNSPECIFIED = 0,

            /** ACTIVE value */
            ACTIVE = 1,

            /** LOW_BATTERY value */
            LOW_BATTERY = 2,

            /** OFFLINE value */
            OFFLINE = 3
        }

        /** FrameType enum. */
        enum FrameType {

            /** FRAME_TYPE_UNSPECIFIED value */
            FRAME_TYPE_UNSPECIFIED = 0,

            /** SNAPSHOT value */
            SNAPSHOT = 1,

            /** BATCH value */
            BATCH = 2
        }

        /**
         * Properties of a DroneState.
         * @deprecated Use assettracker.telemetry.DroneState.$Properties instead.
         */
        interface IDroneState extends assettracker.telemetry.DroneState.$Properties {
        }

        /** Represents a DroneState. */
        class DroneState {

            /**
             * Constructs a new DroneState.
             * @param [properties] Properties to set
             */
            constructor(properties?: assettracker.telemetry.DroneState.$Properties);

            /** Unknown fields preserved while decoding when enabled */
            $unknowns?: Uint8Array[];

            /** DroneState id. */
            id: string;

            /** DroneState lat. */
            lat: number;

            /** DroneState lng. */
            lng: number;

            /** DroneState battery. */
            battery: number;

            /** DroneState status. */
            status: assettracker.telemetry.DroneStatus;

            /**
             * Creates a new DroneState instance using the specified properties.
             * @param [properties] Properties to set
             * @returns DroneState instance
             */
            static create(properties: assettracker.telemetry.DroneState.$Shape): assettracker.telemetry.DroneState & assettracker.telemetry.DroneState.$Shape;
            static create(properties?: assettracker.telemetry.DroneState.$Properties): assettracker.telemetry.DroneState;

            /**
             * Encodes the specified DroneState message. Does not implicitly {@link assettracker.telemetry.DroneState.verify|verify} messages.
             * @param message DroneState message or plain object to encode
             * @param [writer] Writer to encode to
             * @returns Writer
             */
            static encode(message: assettracker.telemetry.DroneState.$Properties, writer?: $protobuf.Writer): $protobuf.Writer;

            /**
             * Encodes the specified DroneState message, length delimited. Does not implicitly {@link assettracker.telemetry.DroneState.verify|verify} messages.
             * @param message DroneState message or plain object to encode
             * @param [writer] Writer to encode to
             * @returns Writer
             */
            static encodeDelimited(message: assettracker.telemetry.DroneState.$Properties, writer?: $protobuf.Writer): $protobuf.Writer;

            /**
             * Decodes a DroneState message from the specified reader or buffer.
             * @param reader Reader or buffer to decode from
             * @param [length] Message length if known beforehand
             * @returns {assettracker.telemetry.DroneState & assettracker.telemetry.DroneState.$Shape} DroneState
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            static decode(reader: ($protobuf.Reader|Uint8Array), length?: number): assettracker.telemetry.DroneState & assettracker.telemetry.DroneState.$Shape;

            /**
             * Decodes a DroneState message from the specified reader or buffer, length delimited.
             * @param reader Reader or buffer to decode from
             * @returns {assettracker.telemetry.DroneState & assettracker.telemetry.DroneState.$Shape} DroneState
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            static decodeDelimited(reader: ($protobuf.Reader|Uint8Array)): assettracker.telemetry.DroneState & assettracker.telemetry.DroneState.$Shape;

            /**
             * Verifies a DroneState message.
             * @param message Plain object to verify
             * @returns `null` if valid, otherwise the reason why it is not
             */
            static verify(message: { [k: string]: any }): (string|null);

            /**
             * Creates a DroneState message from a plain object. Also converts values to their respective internal types.
             * @param object Plain object
             * @returns DroneState
             */
            static fromObject(object: { [k: string]: any }): assettracker.telemetry.DroneState;

            /**
             * Creates a plain object from a DroneState message. Also converts values to other types if specified.
             * @param message DroneState
             * @param [options] Conversion options
             * @returns Plain object
             */
            static toObject(message: assettracker.telemetry.DroneState, options?: $protobuf.IConversionOptions): { [k: string]: any };

            /**
             * Converts this DroneState to JSON.
             * @returns JSON object
             */
            toJSON(): { [k: string]: any };

            /**
             * Gets the type url for DroneState
             * @param [prefix] Custom type url prefix, defaults to `"type.googleapis.com"`
             * @returns The type url
             */
            static getTypeUrl(prefix?: string): string;
        }

        namespace DroneState {

            /** Properties of a DroneState. */
            interface $Properties {

                /** DroneState id */
                id?: (string|null);

                /** DroneState lat */
                lat?: (number|null);

                /** DroneState lng */
                lng?: (number|null);

                /** DroneState battery */
                battery?: (number|null);

                /** DroneState status */
                status?: (assettracker.telemetry.DroneStatus|null);

                /** Unknown fields preserved while decoding when enabled */
                $unknowns?: Uint8Array[];
            }

            /** Shape of a DroneState. */
            type $Shape = assettracker.telemetry.DroneState.$Properties;
        }

        /**
         * Properties of a TelemetryFrame.
         * @deprecated Use assettracker.telemetry.TelemetryFrame.$Properties instead.
         */
        interface ITelemetryFrame extends assettracker.telemetry.TelemetryFrame.$Properties {
        }

        /** Represents a TelemetryFrame. */
        class TelemetryFrame {

            /**
             * Constructs a new TelemetryFrame.
             * @param [properties] Properties to set
             */
            constructor(properties?: assettracker.telemetry.TelemetryFrame.$Properties);

            /** Unknown fields preserved while decoding when enabled */
            $unknowns?: Uint8Array[];

            /** TelemetryFrame type. */
            type: assettracker.telemetry.FrameType;

            /** TelemetryFrame drones. */
            drones: assettracker.telemetry.DroneState.$Properties[];

            /**
             * Creates a new TelemetryFrame instance using the specified properties.
             * @param [properties] Properties to set
             * @returns TelemetryFrame instance
             */
            static create(properties: assettracker.telemetry.TelemetryFrame.$Shape): assettracker.telemetry.TelemetryFrame & assettracker.telemetry.TelemetryFrame.$Shape;
            static create(properties?: assettracker.telemetry.TelemetryFrame.$Properties): assettracker.telemetry.TelemetryFrame;

            /**
             * Encodes the specified TelemetryFrame message. Does not implicitly {@link assettracker.telemetry.TelemetryFrame.verify|verify} messages.
             * @param message TelemetryFrame message or plain object to encode
             * @param [writer] Writer to encode to
             * @returns Writer
             */
            static encode(message: assettracker.telemetry.TelemetryFrame.$Properties, writer?: $protobuf.Writer): $protobuf.Writer;

            /**
             * Encodes the specified TelemetryFrame message, length delimited. Does not implicitly {@link assettracker.telemetry.TelemetryFrame.verify|verify} messages.
             * @param message TelemetryFrame message or plain object to encode
             * @param [writer] Writer to encode to
             * @returns Writer
             */
            static encodeDelimited(message: assettracker.telemetry.TelemetryFrame.$Properties, writer?: $protobuf.Writer): $protobuf.Writer;

            /**
             * Decodes a TelemetryFrame message from the specified reader or buffer.
             * @param reader Reader or buffer to decode from
             * @param [length] Message length if known beforehand
             * @returns {assettracker.telemetry.TelemetryFrame & assettracker.telemetry.TelemetryFrame.$Shape} TelemetryFrame
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            static decode(reader: ($protobuf.Reader|Uint8Array), length?: number): assettracker.telemetry.TelemetryFrame & assettracker.telemetry.TelemetryFrame.$Shape;

            /**
             * Decodes a TelemetryFrame message from the specified reader or buffer, length delimited.
             * @param reader Reader or buffer to decode from
             * @returns {assettracker.telemetry.TelemetryFrame & assettracker.telemetry.TelemetryFrame.$Shape} TelemetryFrame
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            static decodeDelimited(reader: ($protobuf.Reader|Uint8Array)): assettracker.telemetry.TelemetryFrame & assettracker.telemetry.TelemetryFrame.$Shape;

            /**
             * Verifies a TelemetryFrame message.
             * @param message Plain object to verify
             * @returns `null` if valid, otherwise the reason why it is not
             */
            static verify(message: { [k: string]: any }): (string|null);

            /**
             * Creates a TelemetryFrame message from a plain object. Also converts values to their respective internal types.
             * @param object Plain object
             * @returns TelemetryFrame
             */
            static fromObject(object: { [k: string]: any }): assettracker.telemetry.TelemetryFrame;

            /**
             * Creates a plain object from a TelemetryFrame message. Also converts values to other types if specified.
             * @param message TelemetryFrame
             * @param [options] Conversion options
             * @returns Plain object
             */
            static toObject(message: assettracker.telemetry.TelemetryFrame, options?: $protobuf.IConversionOptions): { [k: string]: any };

            /**
             * Converts this TelemetryFrame to JSON.
             * @returns JSON object
             */
            toJSON(): { [k: string]: any };

            /**
             * Gets the type url for TelemetryFrame
             * @param [prefix] Custom type url prefix, defaults to `"type.googleapis.com"`
             * @returns The type url
             */
            static getTypeUrl(prefix?: string): string;
        }

        namespace TelemetryFrame {

            /** Properties of a TelemetryFrame. */
            interface $Properties {

                /** TelemetryFrame type */
                type?: (assettracker.telemetry.FrameType|null);

                /** TelemetryFrame drones */
                drones?: (assettracker.telemetry.DroneState.$Properties[]|null);

                /** Unknown fields preserved while decoding when enabled */
                $unknowns?: Uint8Array[];
            }

            /** Shape of a TelemetryFrame. */
            type $Shape = assettracker.telemetry.TelemetryFrame.$Properties;
        }
    }
}
