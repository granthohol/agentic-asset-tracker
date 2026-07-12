/*eslint-disable block-scoped-var, id-length, no-control-regex, no-magic-numbers, no-mixed-operators, no-prototype-builtins, no-redeclare, no-shadow, no-var, sort-vars, default-case, jsdoc/require-param*/
import $protobuf from "protobufjs/minimal.js";

// Common aliases
const $Reader = $protobuf.Reader, $Writer = $protobuf.Writer, $util = $protobuf.util;
const $Object = $util.global.Object, $undefined = $util.global.undefined, $Error = $util.global.Error, $TypeError = $util.global.TypeError, $String = $util.global.String, $Number = $util.global.Number, $isFinite = $util.global.isFinite, $Array = $util.global.Array;

// Exported root namespace
const $root = $protobuf.roots["default"] || ($protobuf.roots["default"] = {});

export const assettracker = $root.assettracker = (() => {

    /**
     * Namespace assettracker.
     * @exports assettracker
     * @namespace
     */
    const assettracker = {};

    assettracker.telemetry = (function() {

        /**
         * Namespace telemetry.
         * @memberof assettracker
         * @namespace
         */
        const telemetry = {};

        /**
         * DroneStatus enum.
         * @name assettracker.telemetry.DroneStatus
         * @enum {number}
         * @property {number} DRONE_STATUS_UNSPECIFIED=0 DRONE_STATUS_UNSPECIFIED value
         * @property {number} ACTIVE=1 ACTIVE value
         * @property {number} LOW_BATTERY=2 LOW_BATTERY value
         * @property {number} OFFLINE=3 OFFLINE value
         */
        telemetry.DroneStatus = (function() {
            const valuesById = $Object.create(null), values = $Object.create(valuesById);
            values[valuesById[0] = "DRONE_STATUS_UNSPECIFIED"] = 0;
            values[valuesById[1] = "ACTIVE"] = 1;
            values[valuesById[2] = "LOW_BATTERY"] = 2;
            values[valuesById[3] = "OFFLINE"] = 3;
            return values;
        })();

        /**
         * FrameType enum.
         * @name assettracker.telemetry.FrameType
         * @enum {number}
         * @property {number} FRAME_TYPE_UNSPECIFIED=0 FRAME_TYPE_UNSPECIFIED value
         * @property {number} SNAPSHOT=1 SNAPSHOT value
         * @property {number} BATCH=2 BATCH value
         */
        telemetry.FrameType = (function() {
            const valuesById = $Object.create(null), values = $Object.create(valuesById);
            values[valuesById[0] = "FRAME_TYPE_UNSPECIFIED"] = 0;
            values[valuesById[1] = "SNAPSHOT"] = 1;
            values[valuesById[2] = "BATCH"] = 2;
            return values;
        })();

        telemetry.DroneState = (function() {

            /**
             * Properties of a DroneState.
             * @typedef {Object} assettracker.telemetry.DroneState.$Properties
             * @property {string|null} [id] DroneState id
             * @property {number|null} [lat] DroneState lat
             * @property {number|null} [lng] DroneState lng
             * @property {number|null} [battery] DroneState battery
             * @property {assettracker.telemetry.DroneStatus|null} [status] DroneState status
             * @property {Array.<Uint8Array>} [$unknowns] Unknown fields preserved while decoding when enabled
             */

            /**
             * Properties of a DroneState.
             * @memberof assettracker.telemetry
             * @interface IDroneState
             * @augments assettracker.telemetry.DroneState.$Properties
             * @deprecated Use assettracker.telemetry.DroneState.$Properties instead.
             */

            /**
             * Shape of a DroneState.
             * @typedef {assettracker.telemetry.DroneState.$Properties} assettracker.telemetry.DroneState.$Shape
             */

            /**
             * Constructs a new DroneState.
             * @memberof assettracker.telemetry
             * @classdesc Represents a DroneState.
             * @constructor
             * @param {assettracker.telemetry.DroneState.$Properties=} [properties] Properties to set
             * @property {Array.<Uint8Array>} [$unknowns] Unknown fields preserved while decoding when enabled
             */
            const DroneState = function (properties) {
                if (properties)
                    for (let keys = $Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            };

            /**
             * DroneState id.
             * @member {string} id
             * @memberof assettracker.telemetry.DroneState
             * @instance
             */
            DroneState.prototype.id = "";

            /**
             * DroneState lat.
             * @member {number} lat
             * @memberof assettracker.telemetry.DroneState
             * @instance
             */
            DroneState.prototype.lat = 0;

            /**
             * DroneState lng.
             * @member {number} lng
             * @memberof assettracker.telemetry.DroneState
             * @instance
             */
            DroneState.prototype.lng = 0;

            /**
             * DroneState battery.
             * @member {number} battery
             * @memberof assettracker.telemetry.DroneState
             * @instance
             */
            DroneState.prototype.battery = 0;

            /**
             * DroneState status.
             * @member {assettracker.telemetry.DroneStatus} status
             * @memberof assettracker.telemetry.DroneState
             * @instance
             */
            DroneState.prototype.status = 0;

            /**
             * Creates a new DroneState instance using the specified properties.
             * @function create
             * @memberof assettracker.telemetry.DroneState
             * @static
             * @param {assettracker.telemetry.DroneState.$Properties=} [properties] Properties to set
             * @returns {assettracker.telemetry.DroneState} DroneState instance
             * @type {{
             *   (properties: assettracker.telemetry.DroneState.$Shape): assettracker.telemetry.DroneState & assettracker.telemetry.DroneState.$Shape;
             *   (properties?: assettracker.telemetry.DroneState.$Properties): assettracker.telemetry.DroneState;
             * }}
             */
            DroneState.create = function(properties) {
                return new DroneState(properties);
            };

            /**
             * Encodes the specified DroneState message. Does not implicitly {@link assettracker.telemetry.DroneState.verify|verify} messages.
             * @function encode
             * @memberof assettracker.telemetry.DroneState
             * @static
             * @param {assettracker.telemetry.DroneState.$Properties} message DroneState message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            DroneState.encode = function (message, writer, _depth) {
                if (!writer)
                    writer = $Writer.create();
                if (_depth === $undefined)
                    _depth = 0;
                if (_depth > $util.recursionLimit)
                    throw $Error("max depth exceeded");
                if (message.id != null && $Object.hasOwnProperty.call(message, "id") && message.id !== "")
                    writer.uint32(/* id 1, wireType 2 =*/10).string(message.id);
                if (message.lat != null && $Object.hasOwnProperty.call(message, "lat") && !$Object.is(message.lat, 0))
                    writer.uint32(/* id 2, wireType 1 =*/17).double(message.lat);
                if (message.lng != null && $Object.hasOwnProperty.call(message, "lng") && !$Object.is(message.lng, 0))
                    writer.uint32(/* id 3, wireType 1 =*/25).double(message.lng);
                if (message.battery != null && $Object.hasOwnProperty.call(message, "battery") && message.battery !== 0)
                    writer.uint32(/* id 4, wireType 0 =*/32).int32(message.battery);
                if (message.status != null && $Object.hasOwnProperty.call(message, "status") && message.status !== 0)
                    writer.uint32(/* id 5, wireType 0 =*/40).int32(message.status);
                if (message.$unknowns != null && $Object.hasOwnProperty.call(message, "$unknowns"))
                    for (let i = 0; i < message.$unknowns.length; ++i)
                        writer.raw(message.$unknowns[i]);
                return writer;
            };

            /**
             * Encodes the specified DroneState message, length delimited. Does not implicitly {@link assettracker.telemetry.DroneState.verify|verify} messages.
             * @function encodeDelimited
             * @memberof assettracker.telemetry.DroneState
             * @static
             * @param {assettracker.telemetry.DroneState.$Properties} message DroneState message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            DroneState.encodeDelimited = function(message, writer) {
                return this.encode(message, (writer || $Writer.create()).fork()).ldelim();
            };

            /**
             * Decodes a DroneState message from the specified reader or buffer.
             * @function decode
             * @memberof assettracker.telemetry.DroneState
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {assettracker.telemetry.DroneState & assettracker.telemetry.DroneState.$Shape} DroneState
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            DroneState.decode = function (reader, length, _end, _depth, _target) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (_depth === $undefined)
                    _depth = 0;
                if (_depth > $Reader.recursionLimit)
                    throw $Error("max depth exceeded");
                let end = length === $undefined ? reader.len : reader.pos + length, message = _target || new $root.assettracker.telemetry.DroneState(), value;
                while (reader.pos < end) {
                    let start = reader.pos;
                    let tag = reader.tag();
                    if (tag === _end) {
                        _end = $undefined;
                        break;
                    }
                    let wireType = tag & 7;
                    switch (tag >>>= 3) {
                    case 1: {
                            if (wireType !== 2)
                                break;
                            if ((value = reader.stringVerify()).length)
                                message.id = value;
                            else
                                delete message.id;
                            continue;
                        }
                    case 2: {
                            if (wireType !== 1)
                                break;
                            if (!$Object.is(value = reader.double(), 0))
                                message.lat = value;
                            else
                                delete message.lat;
                            continue;
                        }
                    case 3: {
                            if (wireType !== 1)
                                break;
                            if (!$Object.is(value = reader.double(), 0))
                                message.lng = value;
                            else
                                delete message.lng;
                            continue;
                        }
                    case 4: {
                            if (wireType !== 0)
                                break;
                            if (value = reader.int32())
                                message.battery = value;
                            else
                                delete message.battery;
                            continue;
                        }
                    case 5: {
                            if (wireType !== 0)
                                break;
                            if (value = reader.int32())
                                message.status = value;
                            else
                                delete message.status;
                            continue;
                        }
                    }
                    reader.skipType(wireType, _depth, tag);
                    if (!reader.discardUnknown) {
                        $util.makeProp(message, "$unknowns", false);
                        (message.$unknowns || (message.$unknowns = [])).push(reader.raw(start, reader.pos));
                    }
                }
                if (_end !== $undefined)
                    throw $Error("missing end group");
                return message;
            };

            /**
             * Decodes a DroneState message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof assettracker.telemetry.DroneState
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {assettracker.telemetry.DroneState & assettracker.telemetry.DroneState.$Shape} DroneState
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            DroneState.decodeDelimited = function(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a DroneState message.
             * @function verify
             * @memberof assettracker.telemetry.DroneState
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            DroneState.verify = function (message, _depth) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (_depth === $undefined)
                    _depth = 0;
                if (_depth > $util.recursionLimit)
                    return "max depth exceeded";
                if (message.id != null && $Object.hasOwnProperty.call(message, "id"))
                    if (!$util.isString(message.id))
                        return "id: string expected";
                if (message.lat != null && $Object.hasOwnProperty.call(message, "lat"))
                    if (typeof message.lat !== "number")
                        return "lat: number expected";
                if (message.lng != null && $Object.hasOwnProperty.call(message, "lng"))
                    if (typeof message.lng !== "number")
                        return "lng: number expected";
                if (message.battery != null && $Object.hasOwnProperty.call(message, "battery"))
                    if (!$util.isInteger(message.battery))
                        return "battery: integer expected";
                if (message.status != null && $Object.hasOwnProperty.call(message, "status"))
                    if (typeof message.status !== "number" || (message.status | 0) !== message.status)
                        return "status: enum value expected";
                return null;
            };

            /**
             * Creates a DroneState message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof assettracker.telemetry.DroneState
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {assettracker.telemetry.DroneState} DroneState
             */
            DroneState.fromObject = function (object, _depth) {
                if (object instanceof $root.assettracker.telemetry.DroneState)
                    return object;
                if (!$util.isObject(object))
                    throw $TypeError(".assettracker.telemetry.DroneState: object expected");
                if (_depth === $undefined)
                    _depth = 0;
                if (_depth > $util.recursionLimit)
                    throw $Error("max depth exceeded");
                let message = new $root.assettracker.telemetry.DroneState();
                if (object.id != null)
                    if (typeof object.id !== "string" || object.id.length)
                        message.id = $String(object.id);
                if (object.lat != null)
                    if (!$Object.is($Number(object.lat), 0))
                        message.lat = $Number(object.lat);
                if (object.lng != null)
                    if (!$Object.is($Number(object.lng), 0))
                        message.lng = $Number(object.lng);
                if (object.battery != null)
                    if ($Number(object.battery) !== 0)
                        message.battery = object.battery | 0;
                if (object.status !== 0 && (typeof object.status !== "string" || $root.assettracker.telemetry.DroneStatus[object.status] !== 0))
                    switch (object.status) {
                    case "DRONE_STATUS_UNSPECIFIED":
                    case 0:
                        message.status = 0;
                        break;
                    case "ACTIVE":
                    case 1:
                        message.status = 1;
                        break;
                    case "LOW_BATTERY":
                    case 2:
                        message.status = 2;
                        break;
                    case "OFFLINE":
                    case 3:
                        message.status = 3;
                        break;
                    default:
                        if (typeof object.status === "number" && (object.status | 0) === object.status)
                            message.status = object.status;
                    }
                return message;
            };

            /**
             * Creates a plain object from a DroneState message. Also converts values to other types if specified.
             * @function toObject
             * @memberof assettracker.telemetry.DroneState
             * @static
             * @param {assettracker.telemetry.DroneState} message DroneState
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            DroneState.toObject = function (message, options, _depth) {
                if (!options)
                    options = {};
                if (_depth === $undefined)
                    _depth = 0;
                if (_depth > $util.recursionLimit)
                    throw $Error("max depth exceeded");
                let object = {};
                if (options.defaults) {
                    object.id = "";
                    object.lat = 0;
                    object.lng = 0;
                    object.battery = 0;
                    object.status = options.enums === $String ? "DRONE_STATUS_UNSPECIFIED" : 0;
                }
                if (message.id != null && $Object.hasOwnProperty.call(message, "id"))
                    object.id = message.id;
                if (message.lat != null && $Object.hasOwnProperty.call(message, "lat"))
                    object.lat = options.json && !$isFinite(message.lat) ? $String(message.lat) : message.lat;
                if (message.lng != null && $Object.hasOwnProperty.call(message, "lng"))
                    object.lng = options.json && !$isFinite(message.lng) ? $String(message.lng) : message.lng;
                if (message.battery != null && $Object.hasOwnProperty.call(message, "battery"))
                    object.battery = message.battery;
                if (message.status != null && $Object.hasOwnProperty.call(message, "status"))
                    object.status = options.enums === $String ? $root.assettracker.telemetry.DroneStatus[message.status] === $undefined ? message.status : $root.assettracker.telemetry.DroneStatus[message.status] : message.status;
                return object;
            };

            /**
             * Converts this DroneState to JSON.
             * @function toJSON
             * @memberof assettracker.telemetry.DroneState
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            DroneState.prototype.toJSON = function() {
                return DroneState.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the type url for DroneState
             * @function getTypeUrl
             * @memberof assettracker.telemetry.DroneState
             * @static
             * @param {string} [prefix] Custom type url prefix, defaults to `"type.googleapis.com"`
             * @returns {string} The type url
             */
            DroneState.getTypeUrl = function(prefix) {
                if (prefix === $undefined)
                    prefix = "type.googleapis.com";
                return prefix + "/assettracker.telemetry.DroneState";
            };

            return DroneState;
        })();

        telemetry.TelemetryFrame = (function() {

            /**
             * Properties of a TelemetryFrame.
             * @typedef {Object} assettracker.telemetry.TelemetryFrame.$Properties
             * @property {assettracker.telemetry.FrameType|null} [type] TelemetryFrame type
             * @property {Array.<assettracker.telemetry.DroneState.$Properties>|null} [drones] TelemetryFrame drones
             * @property {Array.<Uint8Array>} [$unknowns] Unknown fields preserved while decoding when enabled
             */

            /**
             * Properties of a TelemetryFrame.
             * @memberof assettracker.telemetry
             * @interface ITelemetryFrame
             * @augments assettracker.telemetry.TelemetryFrame.$Properties
             * @deprecated Use assettracker.telemetry.TelemetryFrame.$Properties instead.
             */

            /**
             * Shape of a TelemetryFrame.
             * @typedef {assettracker.telemetry.TelemetryFrame.$Properties} assettracker.telemetry.TelemetryFrame.$Shape
             */

            /**
             * Constructs a new TelemetryFrame.
             * @memberof assettracker.telemetry
             * @classdesc Represents a TelemetryFrame.
             * @constructor
             * @param {assettracker.telemetry.TelemetryFrame.$Properties=} [properties] Properties to set
             * @property {Array.<Uint8Array>} [$unknowns] Unknown fields preserved while decoding when enabled
             */
            const TelemetryFrame = function (properties) {
                this.drones = [];
                if (properties)
                    for (let keys = $Object.keys(properties), i = 0; i < keys.length; ++i)
                        if (properties[keys[i]] != null && keys[i] !== "__proto__")
                            this[keys[i]] = properties[keys[i]];
            };

            /**
             * TelemetryFrame type.
             * @member {assettracker.telemetry.FrameType} type
             * @memberof assettracker.telemetry.TelemetryFrame
             * @instance
             */
            TelemetryFrame.prototype.type = 0;

            /**
             * TelemetryFrame drones.
             * @member {Array.<assettracker.telemetry.DroneState.$Properties>} drones
             * @memberof assettracker.telemetry.TelemetryFrame
             * @instance
             */
            TelemetryFrame.prototype.drones = $util.emptyArray;

            /**
             * Creates a new TelemetryFrame instance using the specified properties.
             * @function create
             * @memberof assettracker.telemetry.TelemetryFrame
             * @static
             * @param {assettracker.telemetry.TelemetryFrame.$Properties=} [properties] Properties to set
             * @returns {assettracker.telemetry.TelemetryFrame} TelemetryFrame instance
             * @type {{
             *   (properties: assettracker.telemetry.TelemetryFrame.$Shape): assettracker.telemetry.TelemetryFrame & assettracker.telemetry.TelemetryFrame.$Shape;
             *   (properties?: assettracker.telemetry.TelemetryFrame.$Properties): assettracker.telemetry.TelemetryFrame;
             * }}
             */
            TelemetryFrame.create = function(properties) {
                return new TelemetryFrame(properties);
            };

            /**
             * Encodes the specified TelemetryFrame message. Does not implicitly {@link assettracker.telemetry.TelemetryFrame.verify|verify} messages.
             * @function encode
             * @memberof assettracker.telemetry.TelemetryFrame
             * @static
             * @param {assettracker.telemetry.TelemetryFrame.$Properties} message TelemetryFrame message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            TelemetryFrame.encode = function (message, writer, _depth) {
                if (!writer)
                    writer = $Writer.create();
                if (_depth === $undefined)
                    _depth = 0;
                if (_depth > $util.recursionLimit)
                    throw $Error("max depth exceeded");
                if (message.type != null && $Object.hasOwnProperty.call(message, "type") && message.type !== 0)
                    writer.uint32(/* id 1, wireType 0 =*/8).int32(message.type);
                if (message.drones != null && message.drones.length)
                    for (let i = 0; i < message.drones.length; ++i)
                        $root.assettracker.telemetry.DroneState.encode(message.drones[i], writer.uint32(/* id 2, wireType 2 =*/18).fork(), _depth + 1).ldelim();
                if (message.$unknowns != null && $Object.hasOwnProperty.call(message, "$unknowns"))
                    for (let i = 0; i < message.$unknowns.length; ++i)
                        writer.raw(message.$unknowns[i]);
                return writer;
            };

            /**
             * Encodes the specified TelemetryFrame message, length delimited. Does not implicitly {@link assettracker.telemetry.TelemetryFrame.verify|verify} messages.
             * @function encodeDelimited
             * @memberof assettracker.telemetry.TelemetryFrame
             * @static
             * @param {assettracker.telemetry.TelemetryFrame.$Properties} message TelemetryFrame message or plain object to encode
             * @param {$protobuf.Writer} [writer] Writer to encode to
             * @returns {$protobuf.Writer} Writer
             */
            TelemetryFrame.encodeDelimited = function(message, writer) {
                return this.encode(message, (writer || $Writer.create()).fork()).ldelim();
            };

            /**
             * Decodes a TelemetryFrame message from the specified reader or buffer.
             * @function decode
             * @memberof assettracker.telemetry.TelemetryFrame
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @param {number} [length] Message length if known beforehand
             * @returns {assettracker.telemetry.TelemetryFrame & assettracker.telemetry.TelemetryFrame.$Shape} TelemetryFrame
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            TelemetryFrame.decode = function (reader, length, _end, _depth, _target) {
                if (!(reader instanceof $Reader))
                    reader = $Reader.create(reader);
                if (_depth === $undefined)
                    _depth = 0;
                if (_depth > $Reader.recursionLimit)
                    throw $Error("max depth exceeded");
                let end = length === $undefined ? reader.len : reader.pos + length, message = _target || new $root.assettracker.telemetry.TelemetryFrame(), value;
                while (reader.pos < end) {
                    let start = reader.pos;
                    let tag = reader.tag();
                    if (tag === _end) {
                        _end = $undefined;
                        break;
                    }
                    let wireType = tag & 7;
                    switch (tag >>>= 3) {
                    case 1: {
                            if (wireType !== 0)
                                break;
                            if (value = reader.int32())
                                message.type = value;
                            else
                                delete message.type;
                            continue;
                        }
                    case 2: {
                            if (wireType !== 2)
                                break;
                            if (!(message.drones && message.drones.length))
                                message.drones = [];
                            message.drones.push($root.assettracker.telemetry.DroneState.decode(reader, reader.uint32(), $undefined, _depth + 1));
                            continue;
                        }
                    }
                    reader.skipType(wireType, _depth, tag);
                    if (!reader.discardUnknown) {
                        $util.makeProp(message, "$unknowns", false);
                        (message.$unknowns || (message.$unknowns = [])).push(reader.raw(start, reader.pos));
                    }
                }
                if (_end !== $undefined)
                    throw $Error("missing end group");
                return message;
            };

            /**
             * Decodes a TelemetryFrame message from the specified reader or buffer, length delimited.
             * @function decodeDelimited
             * @memberof assettracker.telemetry.TelemetryFrame
             * @static
             * @param {$protobuf.Reader|Uint8Array} reader Reader or buffer to decode from
             * @returns {assettracker.telemetry.TelemetryFrame & assettracker.telemetry.TelemetryFrame.$Shape} TelemetryFrame
             * @throws {Error} If the payload is not a reader or valid buffer
             * @throws {$protobuf.util.ProtocolError} If required fields are missing
             */
            TelemetryFrame.decodeDelimited = function(reader) {
                if (!(reader instanceof $Reader))
                    reader = new $Reader(reader);
                return this.decode(reader, reader.uint32());
            };

            /**
             * Verifies a TelemetryFrame message.
             * @function verify
             * @memberof assettracker.telemetry.TelemetryFrame
             * @static
             * @param {Object.<string,*>} message Plain object to verify
             * @returns {string|null} `null` if valid, otherwise the reason why it is not
             */
            TelemetryFrame.verify = function (message, _depth) {
                if (typeof message !== "object" || message === null)
                    return "object expected";
                if (_depth === $undefined)
                    _depth = 0;
                if (_depth > $util.recursionLimit)
                    return "max depth exceeded";
                if (message.type != null && $Object.hasOwnProperty.call(message, "type"))
                    if (typeof message.type !== "number" || (message.type | 0) !== message.type)
                        return "type: enum value expected";
                if (message.drones != null && $Object.hasOwnProperty.call(message, "drones")) {
                    if (!$Array.isArray(message.drones))
                        return "drones: array expected";
                    for (let i = 0; i < message.drones.length; ++i) {
                        let error = $root.assettracker.telemetry.DroneState.verify(message.drones[i], _depth + 1);
                        if (error)
                            return "drones." + error;
                    }
                }
                return null;
            };

            /**
             * Creates a TelemetryFrame message from a plain object. Also converts values to their respective internal types.
             * @function fromObject
             * @memberof assettracker.telemetry.TelemetryFrame
             * @static
             * @param {Object.<string,*>} object Plain object
             * @returns {assettracker.telemetry.TelemetryFrame} TelemetryFrame
             */
            TelemetryFrame.fromObject = function (object, _depth) {
                if (object instanceof $root.assettracker.telemetry.TelemetryFrame)
                    return object;
                if (!$util.isObject(object))
                    throw $TypeError(".assettracker.telemetry.TelemetryFrame: object expected");
                if (_depth === $undefined)
                    _depth = 0;
                if (_depth > $util.recursionLimit)
                    throw $Error("max depth exceeded");
                let message = new $root.assettracker.telemetry.TelemetryFrame();
                if (object.type !== 0 && (typeof object.type !== "string" || $root.assettracker.telemetry.FrameType[object.type] !== 0))
                    switch (object.type) {
                    case "FRAME_TYPE_UNSPECIFIED":
                    case 0:
                        message.type = 0;
                        break;
                    case "SNAPSHOT":
                    case 1:
                        message.type = 1;
                        break;
                    case "BATCH":
                    case 2:
                        message.type = 2;
                        break;
                    default:
                        if (typeof object.type === "number" && (object.type | 0) === object.type)
                            message.type = object.type;
                    }
                if (object.drones) {
                    if (!$Array.isArray(object.drones))
                        throw $TypeError(".assettracker.telemetry.TelemetryFrame.drones: array expected");
                    message.drones = $Array(object.drones.length);
                    for (let i = 0; i < object.drones.length; ++i) {
                        if (!$util.isObject(object.drones[i]))
                            throw $TypeError(".assettracker.telemetry.TelemetryFrame.drones: object expected");
                        message.drones[i] = $root.assettracker.telemetry.DroneState.fromObject(object.drones[i], _depth + 1);
                    }
                }
                return message;
            };

            /**
             * Creates a plain object from a TelemetryFrame message. Also converts values to other types if specified.
             * @function toObject
             * @memberof assettracker.telemetry.TelemetryFrame
             * @static
             * @param {assettracker.telemetry.TelemetryFrame} message TelemetryFrame
             * @param {$protobuf.IConversionOptions} [options] Conversion options
             * @returns {Object.<string,*>} Plain object
             */
            TelemetryFrame.toObject = function (message, options, _depth) {
                if (!options)
                    options = {};
                if (_depth === $undefined)
                    _depth = 0;
                if (_depth > $util.recursionLimit)
                    throw $Error("max depth exceeded");
                let object = {};
                if (options.arrays || options.defaults)
                    object.drones = [];
                if (options.defaults)
                    object.type = options.enums === $String ? "FRAME_TYPE_UNSPECIFIED" : 0;
                if (message.type != null && $Object.hasOwnProperty.call(message, "type"))
                    object.type = options.enums === $String ? $root.assettracker.telemetry.FrameType[message.type] === $undefined ? message.type : $root.assettracker.telemetry.FrameType[message.type] : message.type;
                if (message.drones && message.drones.length) {
                    object.drones = $Array(message.drones.length);
                    for (let j = 0; j < message.drones.length; ++j)
                        object.drones[j] = $root.assettracker.telemetry.DroneState.toObject(message.drones[j], options, _depth + 1);
                }
                return object;
            };

            /**
             * Converts this TelemetryFrame to JSON.
             * @function toJSON
             * @memberof assettracker.telemetry.TelemetryFrame
             * @instance
             * @returns {Object.<string,*>} JSON object
             */
            TelemetryFrame.prototype.toJSON = function() {
                return TelemetryFrame.toObject(this, $protobuf.util.toJSONOptions);
            };

            /**
             * Gets the type url for TelemetryFrame
             * @function getTypeUrl
             * @memberof assettracker.telemetry.TelemetryFrame
             * @static
             * @param {string} [prefix] Custom type url prefix, defaults to `"type.googleapis.com"`
             * @returns {string} The type url
             */
            TelemetryFrame.getTypeUrl = function(prefix) {
                if (prefix === $undefined)
                    prefix = "type.googleapis.com";
                return prefix + "/assettracker.telemetry.TelemetryFrame";
            };

            return TelemetryFrame;
        })();

        return telemetry;
    })();

    return assettracker;
})();

export {
  $root as default
};
