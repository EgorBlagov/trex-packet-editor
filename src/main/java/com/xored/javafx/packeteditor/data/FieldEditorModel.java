package com.xored.javafx.packeteditor.data;

import com.google.common.eventbus.EventBus;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.xored.javafx.packeteditor.events.RebuildViewEvent;
import com.xored.javafx.packeteditor.metatdata.FieldMetadata;
import com.xored.javafx.packeteditor.metatdata.ProtocolMetadata;
import com.xored.javafx.packeteditor.scapy.*;
import com.xored.javafx.packeteditor.service.IMetadataService;
import com.xored.javafx.packeteditor.service.PacketDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

public class FieldEditorModel {

    private Logger logger= LoggerFactory.getLogger(FieldEditorModel.class);
    
    private Stack<Protocol> protocols = new Stack<>();

    /**
     * Current packet representation in ScapyService format
     */
    ScapyPkt pkt = new ScapyPkt();

    File currentFile;

    @Inject
    EventBus eventBus;

    @Inject
    IBinaryData binary;
    
    @Inject
    PacketDataService packetDataService;

    Stack<ScapyPkt> undoRecords = new Stack<>();
    
    Stack<ScapyPkt> redoRecords = new Stack<>();
    
    Stack<ScapyPkt> undoingFrom;
    
    Stack<ScapyPkt> undoingTo;
    
    private IMetadataService metadataService;

    public Protocol getCurrentProtocol() {
        try {
            return protocols.peek();
        } catch (EmptyStackException e) {
            return null;
        }
    }
    
    public void deleteAllProtocols() {
        protocols.clear();
        fireUpdateViewEvent();
    }
    
    public void addProtocol(ProtocolMetadata meta) {
        ScapyPkt newPkt = packetDataService.appendProtocol(pkt, meta.getId());
        setPktAndReload(newPkt);
        logger.info("Protocol {} added.", meta.getName());
    }


    public List<ProtocolMetadata> getAvailableProtocolsToAdd() {
        Map<String, ProtocolMetadata>  protocolsMetaMap = metadataService.getProtocols();
        if (protocols.size() == 0) {
            return Arrays.asList(metadataService.getProtocolMetadataById("Ether"));
        }
        return protocols.peek().getMeta().getPayload()
                .stream().map(protocolsMetaMap::get)
                .filter(item -> item != null) // Filter empty entries for undefined Protocols
                .collect(Collectors.toList());
    }
    
    private Protocol buildProtocolFromMeta(ProtocolMetadata meta) {
        return new Protocol(meta, getCurrentPath());
    }
    
    private List<String> getCurrentPath() {
        return protocols.stream().map(Protocol::getId).collect(Collectors.toList());
    }

    public void setMetadataService(IMetadataService metadataService) {
        this.metadataService = metadataService;
    }

    public void removeLast() {
        undoRecords.push(pkt);
        ScapyPkt newPkt = packetDataService.removeLastProtocol(pkt);
        setPktAndReload(newPkt);
    }

    private void fireUpdateViewEvent() {
        eventBus.post(new RebuildViewEvent(protocols));
    }

    public void setPktAndReload(ScapyPkt pkt){
        beforeContentReplace(this.pkt);
        this.pkt = pkt;
        reload();
    }
    public void reload () {
        protocols.clear();
        PacketData packet = pkt.packet();
        
        binary.setBytes(packet.getPacketBytes());

        for (ProtocolData protocol: packet.getProtocols()) {
            ProtocolMetadata protocolMetadata = metadataService.getProtocolMetadata(protocol);
            Protocol protocolObj = buildProtocolFromMeta(protocolMetadata);
            protocols.push(protocolObj);

            Integer protocolOffset = protocol.offset.intValue();
            for (FieldData field: protocol.fields) {
                Field fieldObj = new Field(protocolMetadata.getMetaForField(field.id), getCurrentPath(), protocolOffset, field);
                fieldObj.setOnSetCallback(newValue -> {
                    this.editField(fieldObj, newValue);
                });
                protocolObj.getFields().add(fieldObj);
            }
        }
        fireUpdateViewEvent();
    }

    public void editField(Field field, ReconstructField newValue) {
        assert(field.getId() == newValue.id);
        ScapyPkt newPkt = packetDataService.setFieldValue(pkt, field, newValue);
        setPktAndReload(newPkt);
    }

    /** sets text value */
    public void editField(Field field, String newValue) {
        if (field.getData().getValueExpr() != null) {
            // if original value was expression, which means there are no good representation for it,
            // new string value should be treated as an expression as well. not as a hvalue
            // at least until we do not improve support for h2i/i2h
            editField(field, ReconstructField.setExpressionValue(field.getId(), newValue));
        } else {
            editField(field, ReconstructField.setHumanValue(field.getId(), newValue));
        }
    }

    public void setSelected(Field field) {
        binary.setSelected(field.getAbsOffset(), field.getLength());
    }

    /** should be called when modification is done */
    public void beforeContentReplace(ScapyPkt oldPkt) {
        if (undoingFrom == null) {
            // new user change
            undoRecords.push(oldPkt);
            redoRecords.clear();
        } else if (undoingFrom != null) {
            // undoing or redoing
            undoingTo.push(oldPkt);
        }
    }

    void doUndo(Stack<ScapyPkt> from, Stack<ScapyPkt> to) {
        if (from.empty()) {
            logger.debug("Nothing to undo/redo");
            return;
        }
        try {
            undoingFrom = from;
            undoingTo = to;
            setPktAndReload(from.pop());
        } catch (Exception e) {
            logger.error("undo/redo failed", e);
        } finally {
            undoingFrom = null;
            undoingTo = null;
        }
        
    }

    public void undo() {
        doUndo(undoRecords, redoRecords);
    }

    public void redo() {
        doUndo(redoRecords, undoRecords);
    }

    /* Reset length and chksum fields
     * type fields can be calculated for layers with payload
     *  */
    public ScapyPkt recalculateAutoValues(ScapyPkt pkt) {
        List<ProtocolData> protocols = pkt.packet().getProtocols();
        List<ReconstructProtocol> modify = protocols.stream().map(
                protocol -> {
                    boolean is_last_layer = protocol == protocols.get(protocols.size() - 1);
                    return ReconstructProtocol.modify(protocol.id, protocol.fields.stream().filter(field ->
                                    field.id.equals("length") ||
                                            field.id.equals("chksum") ||
                                            (field.id.equals("type") && is_last_layer)
                    ).map(f -> ReconstructField.resetValue(f.id)).collect(Collectors.toList()));
                }).collect(Collectors.toList());
        return packetDataService.reconstructPacket(pkt, modify);
    }
    
    private void clearHistory() {
        undoRecords.clear();
        redoRecords.clear();
    }

    public ScapyPkt getPkt() {
        return pkt;
    }

    public void newPacket() {
        clearHistory();
        pkt = new ScapyPkt();
        reload();
    }

    public File getCurrentFile() {
        return currentFile;
    }

    public void setCurrentFile(File currentFile) {
        this.currentFile = currentFile;
        clearHistory();
    }

}
